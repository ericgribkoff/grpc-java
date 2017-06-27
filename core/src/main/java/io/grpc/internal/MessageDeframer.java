/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Codec;
import io.grpc.Decompressor;
import io.grpc.Status;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Deframer for GRPC frames.
 *
 * <p>This class is not thread-safe. All calls to public methods should be made in the transport
 * thread.
 */
@NotThreadSafe
public class MessageDeframer implements Closeable {
  private static final int HEADER_LENGTH = 5;
  private static final int COMPRESSED_FLAG_MASK = 1;
  private static final int RESERVED_MASK = 0xFE;

  /**
   * A listener of deframing events.
   */
  public interface Listener {

    /**
     * Called when the given number of bytes has been read from the input source of the deframer.
     * This is typically used to indicate to the underlying transport that more data can be
     * accepted.
     *
     * @param numBytes the number of bytes read from the deframer's input source.
     */
    void bytesRead(int numBytes);

    /**
     * Called to deliver the next complete message.
     *
     * @param message stream containing the message.
     */
    void messageRead(InputStream message);

    /**
     * Called when the deframer closes.
     */
    void deframerClosed();
  }

  private enum State {
    HEADER, BODY
  }

  private final Listener listener;
  private int maxInboundMessageSize;
  private final StatsTraceContext statsTraceCtx;
  private final String debugString;
  private Decompressor decompressor;
  private State state = State.HEADER;
  private int requiredLength = HEADER_LENGTH;
  private boolean compressedFlag;
  private CompositeReadableBuffer nextFrame;
  private CompositeReadableBuffer unprocessed = new CompositeReadableBuffer();
  private long pendingDeliveries;
  private boolean inDelivery = false;

  private boolean closeWhenComplete = false;

  /**
   * Create a deframer.
   *
   * @param listener listener for deframer events.
   * @param decompressor the compression used if a compressed frame is encountered, with
   *  {@code NONE} meaning unsupported
   * @param maxMessageSize the maximum allowed size for received messages.
   * @param debugString a string that will appear on errors statuses
   */
  public MessageDeframer(Listener listener, Decompressor decompressor, int maxMessageSize,
      StatsTraceContext statsTraceCtx, String debugString) {
    this.listener = Preconditions.checkNotNull(listener, "sink");
    this.decompressor = Preconditions.checkNotNull(decompressor, "decompressor");
    this.maxInboundMessageSize = maxMessageSize;
    this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
    this.debugString = debugString;
  }

  void setMaxInboundMessageSize(int messageSize) {
    maxInboundMessageSize = messageSize;
  }

  /**
   * Sets the decompressor available to use.  The message encoding for the stream comes later in
   * time, and thus will not be available at the time of construction.  This should only be set
   * once, since the compression codec cannot change after the headers have been sent.
   *
   * @param decompressor the decompressing wrapper.
   */
  public void setDecompressor(Decompressor decompressor) {
    this.decompressor = checkNotNull(decompressor, "Can't pass an empty decompressor");
  }

  /**
   * Requests up to the given number of messages from the call to be delivered to
   * {@link Listener#messageRead(InputStream)}. No additional messages will be delivered.
   *
   * <p>If {@link #close()} has been called, this method will have no effect.
   *
   * @param numMessages the requested number of messages to be delivered to the listener.
   */
  public void request(int numMessages) {
    Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
    if (isClosed()) {
      return;
    }
    pendingDeliveries += numMessages;
    deliver();
  }

  /**
   * Adds the given data to this deframer and attempts delivery to the listener.
   *
   * @param data the raw data read from the remote endpoint. Must be non-null.
   * @throws IllegalStateException if {@link #close()} or {@link #closeWhenComplete()} has been
   *     called previously.
   */
  public void deframe(ReadableBuffer data) {
    Preconditions.checkNotNull(data, "data");
    boolean needToCloseData = true;
    try {
      checkNotClosedOrScheduledToClose();

      unprocessed.addBuffer(data);
      needToCloseData = false;

      deliver();
    } finally {
      if (needToCloseData) {
        data.close();
      }
    }
  }

  /** Close when any messages currently in unprocessed have been requested and delivered. */
  public void closeWhenComplete() {
    if (unprocessed == null) {
      return;
    }
    boolean stalled = unprocessed.readableBytes() == 0;
    if (stalled) {
      close();
    } else {
      closeWhenComplete = true;
    }
  }

  /**
   * Closes this deframer and frees any resources. After this method is called, additional
   * calls will have no effect.
   */
  @Override
  public void close() {
    if (isClosed()) {
      return;
    }
    try {
      if (unprocessed != null) {
        unprocessed.close();
      }
      if (nextFrame != null) {
        nextFrame.close();
      }
      listener.deframerClosed();
    } finally {
      unprocessed = null;
      nextFrame = null;
    }
  }

  /**
   * Indicates whether or not this deframer has been closed.
   */
  public boolean isClosed() {
    return unprocessed == null;
  }

  /**
   * Throws if this deframer has already been closed.
   */
  private void checkNotClosedOrScheduledToClose() {
    Preconditions.checkState(!isClosed(), "MessageDeframer is already closed");
    Preconditions.checkState(!closeWhenComplete, "MessageDeframer is scheduled to close");
  }

  /**
   * Reads and delivers as many messages to the listener as possible.
   */
  private void deliver() {
    // We can have reentrancy here when using a direct executor, triggered by calls to
    // request more messages. This is safe as we simply loop until pendingDelivers = 0
    if (inDelivery) {
      return;
    }
    inDelivery = true;
    try {
      // Process the uncompressed bytes.
      while (pendingDeliveries > 0 && readRequiredBytes()) {
        switch (state) {
          case HEADER:
            processHeader();
            break;
          case BODY:
            // Read the body and deliver the message.
            processBody();

            // Since we've delivered a message, decrement the number of pending
            // deliveries remaining.
            pendingDeliveries--;
            break;
          default:
            throw new AssertionError("Invalid state: " + state);
        }
      }

      /*
       * We are stalled when there are no more bytes to process. This allows delivering errors as
       * soon as the buffered input has been consumed, independent of whether the application
       * has requested another message.  At this point in the function, either all frames have been
       * delivered, or unprocessed is empty.  If there is a partial message, it will be inside next
       * frame and not in unprocessed.  If there is extra data but no pending deliveries, it will
       * be in unprocessed.
       */
      boolean stalled = unprocessed.readableBytes() == 0;
      if (closeWhenComplete && stalled) {
        close();
      }
    } finally {
      inDelivery = false;
    }
  }

  /**
   * Attempts to read the required bytes into nextFrame.
   *
   * @return {@code true} if all of the required bytes have been read.
   */
  private boolean readRequiredBytes() {
    int totalBytesRead = 0;
    try {
      if (nextFrame == null) {
        nextFrame = new CompositeReadableBuffer();
      }

      // Read until the buffer contains all the required bytes.
      int missingBytes;
      while ((missingBytes = requiredLength - nextFrame.readableBytes()) > 0) {
        if (unprocessed.readableBytes() == 0) {
          // No more data is available.
          return false;
        }
        int toRead = Math.min(missingBytes, unprocessed.readableBytes());
        totalBytesRead += toRead;
        nextFrame.addBuffer(unprocessed.readBytes(toRead));
      }
      return true;
    } finally {
      if (totalBytesRead > 0) {
        listener.bytesRead(totalBytesRead);
        if (state == State.BODY) {
          statsTraceCtx.inboundWireSize(totalBytesRead);
        }
      }
    }
  }

  /**
   * Processes the GRPC compression header which is composed of the compression flag and the outer
   * frame length.
   */
  private void processHeader() {
    int type = nextFrame.readUnsignedByte();
    if ((type & RESERVED_MASK) != 0) {
      throw Status.INTERNAL.withDescription(
          debugString + ": Frame header malformed: reserved bits not zero")
          .asRuntimeException();
    }
    compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

    // Update the required length to include the length of the frame.
    requiredLength = nextFrame.readInt();
    if (requiredLength < 0 || requiredLength > maxInboundMessageSize) {
      throw Status.RESOURCE_EXHAUSTED.withDescription(
          String.format("%s: Frame size %d exceeds maximum: %d. ",
              debugString, requiredLength, maxInboundMessageSize))
          .asRuntimeException();
    }

    statsTraceCtx.inboundMessage();
    // Continue reading the frame body.
    state = State.BODY;
  }

  /**
   * Processes the GRPC message body, which depending on frame header flags may be compressed.
   */
  private void processBody() {
    InputStream stream = compressedFlag ? getCompressedBody() : getUncompressedBody();
    nextFrame = null;
    listener.messageRead(stream);

    // Done with this frame, begin processing the next header.
    state = State.HEADER;
    requiredLength = HEADER_LENGTH;
  }

  private InputStream getUncompressedBody() {
    statsTraceCtx.inboundUncompressedSize(nextFrame.readableBytes());
    return ReadableBuffers.openStream(nextFrame, true);
  }

  private InputStream getCompressedBody() {
    if (decompressor == Codec.Identity.NONE) {
      throw Status.INTERNAL.withDescription(
          debugString + ": Can't decode compressed frame as compression not configured.")
          .asRuntimeException();
    }

    try {
      // Enforce the maxMessageSize limit on the returned stream.
      InputStream unlimitedStream =
          decompressor.decompress(ReadableBuffers.openStream(nextFrame, true));
      return new SizeEnforcingInputStream(
          unlimitedStream, maxInboundMessageSize, statsTraceCtx, debugString);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * An {@link InputStream} that enforces the {@link #maxMessageSize} limit for compressed frames.
   */
  @VisibleForTesting
  static final class SizeEnforcingInputStream extends FilterInputStream {
    private final int maxMessageSize;
    private final StatsTraceContext statsTraceCtx;
    private final String debugString;
    private long maxCount;
    private long count;
    private long mark = -1;

    SizeEnforcingInputStream(InputStream in, int maxMessageSize, StatsTraceContext statsTraceCtx,
        String debugString) {
      super(in);
      this.maxMessageSize = maxMessageSize;
      this.statsTraceCtx = statsTraceCtx;
      this.debugString = debugString;
    }

    @Override
    public int read() throws IOException {
      int result = in.read();
      if (result != -1) {
        count++;
      }
      verifySize();
      reportCount();
      return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int result = in.read(b, off, len);
      if (result != -1) {
        count += result;
      }
      verifySize();
      reportCount();
      return result;
    }

    @Override
    public long skip(long n) throws IOException {
      long result = in.skip(n);
      count += result;
      verifySize();
      reportCount();
      return result;
    }

    @Override
    public synchronized void mark(int readlimit) {
      in.mark(readlimit);
      mark = count;
      // it's okay to mark even if mark isn't supported, as reset won't work
    }

    @Override
    public synchronized void reset() throws IOException {
      if (!in.markSupported()) {
        throw new IOException("Mark not supported");
      }
      if (mark == -1) {
        throw new IOException("Mark not set");
      }

      in.reset();
      count = mark;
    }

    private void reportCount() {
      if (count > maxCount) {
        statsTraceCtx.inboundUncompressedSize(count - maxCount);
        maxCount = count;
      }
    }

    private void verifySize() {
      if (count > maxMessageSize) {
        throw Status.RESOURCE_EXHAUSTED.withDescription(String.format(
                "%s: Compressed frame exceeds maximum frame size: %d. Bytes read: %d. ",
                debugString, maxMessageSize, count)).asRuntimeException();
      }
    }
  }
}
