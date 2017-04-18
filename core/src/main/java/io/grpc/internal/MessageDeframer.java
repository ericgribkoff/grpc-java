/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Codec;
import io.grpc.Decompressor;
import io.grpc.Status;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Deframer for GRPC frames.
 *
 * <p>This class is not thread-safe. All calls to public methods should be made in the transport
 * thread.
 */
@NotThreadSafe
public class MessageDeframer {
  private static final int HEADER_LENGTH = 5;
  private static final int COMPRESSED_FLAG_MASK = 1;
  private static final int RESERVED_MASK = 0xFE;

  /**
   * A listener of deframing events.
   */
  public interface Listener {
    /**
     * Called to schedule deframing in the application thread. Invoked from transport thread.
     */
    void messageProducerAvailable(MessageProducer mp);

    /**
     * Called when the given number of bytes has been read from the input source of the deframer.
     * This is typically used to indicate to the underlying transport that more data can be
     * accepted. Invoked from the message producer thread.
     *
     * @param numBytes the number of bytes read from the deframer's input source.
     */
    void bytesRead(int numBytes);

    /**
     * Called when end-of-stream has not yet been reached but there are no complete messages
     * remaining to be delivered. Invoked from the message producer thread.
     */
    void deliveryStalled();

    /**
     * Called when the stream is complete and all messages have been successfully delivered.
     * Invoked from the message producer thread.
     */
    void endOfStream();

    /**
     * Called when deframe fails. Invoked from the message producer thread.
     */
    void deframeFailed(Throwable t);
  }

  /**
   * A message producing object.
   */
  public interface MessageProducer {
    @Nullable
    InputStream next();

    @VisibleForTesting
    void checkEndOfStreamOrStalled();
  }


  private enum State {
    HEADER, BODY
  }

  private final Listener listener;
  private final StatsTraceContext statsTraceCtx;
  private final String debugString;
  private final Producer producer = new Producer();

  private CompositeReadableBuffer unprocessed = new CompositeReadableBuffer();
  private final AtomicInteger pendingDeliveries = new AtomicInteger();
  private volatile int maxInboundMessageSize; // only written by transport thread.
  private volatile Decompressor decompressor; // only written by transport thread.
  private volatile boolean endOfStream; // only written by transport thread
  private volatile boolean closed; // only written by client thread
  private volatile boolean closeScheduled; // only written by transport thread

  /**
   * Create a deframer.
   *
   * @param listener listener for deframer events.
   * @param decompressor the compression used if a compressed frame is encountered, with {@code
   *   NONE} meaning unsupported
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
   * Requests up to the given number of messages from the call to be delivered via {@link
   * Listener#messageProducerAvailable(MessageProducer)}. No additional messages will be delivered.
   *
   * <p>If already closed, this method will have no effect.
   *
   * @param numMessages the requested number of messages to be delivered to the listener.
   */
  public void request(int numMessages) {
    Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
    if (isScheduledToClose()) {
      return;
    }
    pendingDeliveries.getAndAdd(numMessages);
    listener.messageProducerAvailable(producer);
  }

  /**
   * Adds the given data to this deframer and attempts delivery to the listener.
   *
   * @param data the raw data read from the remote endpoint. Must be non-null.
   * @param endOfStream if {@code true}, indicates that {@code data} is the end of the stream from
   *     the remote endpoint. End of stream should not be used in the event of a transport error,
   *     such as a stream reset.
   * @throws IllegalStateException if already scheduled to close or if this method has previously
   *     been called with {@code endOfStream=true}.
   */
  public void deframe(ReadableBuffer data, boolean endOfStream) {
    Preconditions.checkNotNull(data, "data");
    boolean needToCloseData = true;
    try {
      checkNotScheduledToClose();
      Preconditions.checkState(!this.endOfStream, "Past end of stream");

      unprocessed.addBuffer(data);
      needToCloseData = false;

      // Indicate that all of the data for this stream has been received.
      this.endOfStream = endOfStream;
      listener.messageProducerAvailable(producer);
    } finally {
      if (needToCloseData) {
        data.close();
      }
    }
  }

  /**
   * Indicates whether delivery is currently stalled, pending receipt of more data.  This means
   * that no additional data can be delivered to the application.
   */
  // TODO(ericgribkoff) Handle when unprocessed contains data but not enough to process a complete
  // message.
  public boolean isStalled() {
    // Needs to be checked dynamically, since stalled will only be updated once we start processing
    // the data. This won't happen right away if the client has not request()ed any additional
    // messages.
    return unprocessed.readableBytes() == 0;
  }

  /**
   * Schedule close in the client thread once any buffered messages are processed. Called from
   * transport thread.
   */
  public void scheduleClose() {
    closeScheduled = true;
    if (!closed) {
      // make sure that the message producer will see the schedule close.
      listener.messageProducerAvailable(producer);
    }
  }

  /**
   * Indicates whether or not this deframer has been closed.
   */
  public boolean isScheduledToClose() {
    return closed || closeScheduled;
  }

  /**
   * Throws if this deframer has already been closed.
   */
  private void checkNotScheduledToClose() {
    Preconditions.checkState(!isScheduledToClose(), "MessageDeframer already scheduled to close");
  }

  private class Producer implements MessageProducer {
    private State state = State.HEADER;
    private int requiredLength = HEADER_LENGTH;
    private boolean compressedFlag;
    private boolean inDelivery = false;

    private CompositeReadableBuffer nextFrame;

    private void close() {
      closed = true;
      try {
        if (unprocessed != null) {
          unprocessed.close();
        }
        if (nextFrame != null) {
          nextFrame.close();
        }
      } finally {
        unprocessed = null;
        nextFrame = null;
      }
    }

    /**
     * Reads and delivers as many messages to the listener as possible.
     */
    @Override
    public InputStream next() {
      if (closed) {
        return null;
      }
      if (inDelivery) {
        return null;
      }
      inDelivery = true;

      try {
        // Process the uncompressed bytes.
        while (pendingDeliveries.get() > 0 && readRequiredBytes()) {
          switch (state) {
            // TODO(ericgribkoff) Rather than return boolean to indicate error from processHeader()
            //   and processBody(), set an error member variable?
            case HEADER:
              if (!processHeader()) {
                // Error occurred
                return null;
              }
              break;
            case BODY:
              InputStream toReturn;
              // Read the body and deliver the message.
              if ((toReturn = processBody()) == null) {
                // Error occurred
                return null;
              }
              // Since we've delivered a message, decrement the number of pending
              // deliveries remaining.
              pendingDeliveries.getAndDecrement();
              return toReturn;
            default:
              AssertionError t = new AssertionError("Invalid state: " + state);
              listener.deframeFailed(t);
              return null;
          }
        }
        checkEndOfStreamOrStalled();
        return null;
      } finally {
        inDelivery = false;
      }
    }

    @Override
    public void checkEndOfStreamOrStalled() {
      //TODO(ericgribkoff) If tests can be modified, make this private and not part of interface.
      if (closed) {
        return;
      }
      /*
       * We are stalled when there are no more bytes to process. This allows delivering errors as
       * soon as the buffered input has been consumed, independent of whether the application
       * has requested another message.  At this point in the function, either all frames have been
       * delivered, or unprocessed is empty.  If there is a partial message, it will be inside next
       * frame and not in unprocessed.  If there is extra data but no pending deliveries, it will
       * be in unprocessed.
       */
      boolean stalled;
      stalled = unprocessed.readableBytes() == 0;

      if (stalled && closeScheduled) {
        // The transport already scheduled a close, so we do not need to notify it.
        close();
        return;
      }

      if (endOfStream && stalled) {
        boolean havePartialMessage = nextFrame != null && nextFrame.readableBytes() > 0;
        if (!havePartialMessage) {
          // Have to be careful with this on the server if we move deframing to the app thread:
          // we could end up seeing endOfStream=stalled=true but have data in unprocessed since
          // deframe() is not atomic. Ok for clients, as endOfStream() just calls deliveryStalled().
          // Further, since endOfStream() ends up calling onCompleted() on the local stream
          // observer, it must only be called once or a guard added to AbstractServerStream.
          listener.endOfStream();
        } else {
          // We've received the entire stream and have data available but we don't have
          // enough to read the next frame ... this is bad.
          RuntimeException t = Status.INTERNAL.withDescription(
              debugString + ": Encountered end-of-stream mid-frame").asRuntimeException();
          listener.deframeFailed(t);
        }
        return;
      }

      if (stalled) {
        // Always notify if stalled. Otherwise, the transport may schedule a message on a stalled
        // deframer, then receive the trailers and set their delivery to the client as the
        // deliveryStalled task. When the deframer runs in the app thread and processes the message,
        // stalled won't have flipped and the trailers will never be deliver.
        // TODO(ericgribkoff) Could add volatile boolean in MessageDeframer "waitingForStalled" that
        //   the transport could set.
        listener.deliveryStalled();
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
     *
     * @return false if error occurred
     */
    private boolean processHeader() {
      int type = nextFrame.readUnsignedByte();
      if ((type & RESERVED_MASK) != 0) {
        RuntimeException t = Status.INTERNAL.withDescription(
            debugString + ": Frame header malformed: reserved bits not zero")
            .asRuntimeException();
        listener.deframeFailed(t);
        return false;
      }
      compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

      // Update the required length to include the length of the frame.
      requiredLength = nextFrame.readInt();
      if (requiredLength < 0 || requiredLength > maxInboundMessageSize) {
        RuntimeException t = Status.RESOURCE_EXHAUSTED.withDescription(
            String.format("%s: Frame size %d exceeds maximum: %d. ",
                debugString, requiredLength, maxInboundMessageSize)).asRuntimeException();
        listener.deframeFailed(t);
        return false;
      }
      statsTraceCtx.inboundMessage();
      // Continue reading the frame body.
      state = State.BODY;
      return true;
    }

    /**
     * Processes the GRPC message body, which depending on frame header flags may be compressed.
     *
     * @return null if error occurred
     */
    @Nullable
    private InputStream processBody() {
      InputStream stream = compressedFlag ? getCompressedBody() : getUncompressedBody();
      nextFrame = null;

      // Done with this frame, begin processing the next header.
      state = State.HEADER;
      requiredLength = HEADER_LENGTH;

      return stream;
    }

    private InputStream getUncompressedBody() {
      statsTraceCtx.inboundUncompressedSize(nextFrame.readableBytes());
      return ReadableBuffers.openStream(nextFrame, true);
    }

    /**
     * Deframes the compressed body.
     *
     * @return null if error occurred
     */
    @Nullable
    private InputStream getCompressedBody() {
      if (decompressor == Codec.Identity.NONE) {
        RuntimeException t = Status.INTERNAL.withDescription(
            debugString + ": Can't decode compressed frame as compression not configured.")
            .asRuntimeException();
        listener.deframeFailed(t);
        return null;
      }

      try {
        // Enforce the maxMessageSize limit on the returned stream.
        InputStream unlimitedStream =
            decompressor.decompress(ReadableBuffers.openStream(nextFrame, true));
        return new SizeEnforcingInputStream(
            unlimitedStream, maxInboundMessageSize, statsTraceCtx, debugString);
      } catch (IOException e) {
        RuntimeException t = new RuntimeException(e);
        listener.deframeFailed(t);
        return null;
      }
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
