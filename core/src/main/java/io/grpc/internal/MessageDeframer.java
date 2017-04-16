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
import java.util.concurrent.ConcurrentLinkedQueue;
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
   * A message producing object.
   */
  public interface MessageProducer {
    @Nullable
    InputStream next();

    void checkEndOfStreamOrStalled();

    void close();
  }

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
     * @param is stream containing the message.
     */
    void messageRead(InputStream is);

    /**
     * Called to schedule deframing in the application thread.
     */
    void messagesAvailable(MessageProducer mp);

    /**
     * Called when end-of-stream has not yet been reached but there are no complete messages
     * remaining to be delivered.
     */
    void deliveryStalled();

    /**
     * Called when the stream is complete and all messages have been successfully delivered.
     */
    void endOfStream();

    /**
     * Called when deframe fails.
     */
    void deframeFailed(Throwable t);
  }

  private enum State {
    HEADER, BODY
  }

  private final Listener listener;
  private final StatsTraceContext statsTraceCtx;
  private final String debugString;
  final Producer producer = new Producer();

  // Shared state - can copy this to the message producer rather than updating it here, which would
  //   maintain a notion of happens-before. Would need to figure out how to share unprocessed across
  //   multiple Producers, that might have read a header but not the message, etc.
  private volatile int maxInboundMessageSize; // only written by transport thread.
  private volatile Decompressor decompressor; // only written by transport thread.
  private volatile boolean endOfStream; // only written by transport thread
  private final AtomicInteger pendingDeliveries = new AtomicInteger();
  private volatile boolean deliveryStalled = true; // only written by client thread
  private volatile boolean closed; // only written by client thread

  // Audit this
  private CompositeReadableBuffer unprocessed =
      new CompositeReadableBuffer(new ConcurrentLinkedQueue<ReadableBuffer>());

  /**
   * Create a deframer.
   *
   * @param listener       listener for deframer events.
   * @param decompressor   the compression used if a compressed frame is encountered, with {@code
   *                       NONE} meaning unsupported
   * @param maxMessageSize the maximum allowed size for received messages.
   * @param debugString    a string that will appear on errors statuses
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
   * <p>If {@link MessageProducer#close()} has been called, this method will have no effect.
   *
   * @param numMessages the requested number of messages to be delivered to the listener.
   */
  public void request(int numMessages) {
    Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
    if (isClosed()) {
      return;
    }
    pendingDeliveries.getAndAdd(numMessages);
    listener.messagesAvailable(producer);
  }

  /**
   * Adds the given data to this deframer and attempts delivery to the listener.
   *
   * @param data        the raw data read from the remote endpoint. Must be non-null.
   * @param endOfStream if {@code true}, indicates that {@code data} is the end of the stream from
   *                    the remote endpoint.  End of stream should not be used in the event of a
   *                    transport error, such as a stream reset.
   * @throws IllegalStateException if {@link MessageProducer#close()} has been called previously or
   *                               if this method has previously been called with {@code
   *                               endOfStream=true}.
   */
  public void deframe(ReadableBuffer data, boolean endOfStream) {
    Preconditions.checkNotNull(data, "data");
    boolean needToCloseData = true;
    try {
      checkNotClosed();
      Preconditions.checkState(!this.endOfStream, "Past end of stream");

      unprocessed.addBuffer(data);
      needToCloseData = false;

      // Indicate that all of the data for this stream has been received.
      this.endOfStream = endOfStream;
      listener.messagesAvailable(producer);
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
  public boolean isStalled() {
    return unprocessed == null || unprocessed.readableBytes() == 0;
  }


  /**
   * Indicates whether or not this deframer has been closed.
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Throws if this deframer has already been closed.
   */
  private void checkNotClosed() {
    Preconditions.checkState(!isClosed(), "MessageDeframer is already closed");
  }

  public MessageProducer getProducer() {
    return producer;
  }

  private class Producer implements MessageProducer {
    private State state = State.HEADER;
    private int requiredLength = HEADER_LENGTH;
    private boolean compressedFlag;
    private boolean inDelivery = false;

    private CompositeReadableBuffer nextFrame;

    @Override
    public void close() {
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
      Preconditions.checkState(!closed, "closed");
      // We can have reentrancy here when using a direct executor, triggered by calls to
      // request more messages. This is safe as we simply loop until pendingDelivers = 0
      // TODO(ericgribkoff) Make sure this is safe because the loop is no longer in this class
      if (inDelivery) {
        return null;
      }
      inDelivery = true;

      InputStream toReturn = null;
      try {
        // Process the uncompressed bytes.
        while (toReturn == null && pendingDeliveries.get() > 0 && readRequiredBytes()) {
          switch (state) {
            case HEADER:
              processHeader();
              break;
            case BODY:
              // Read the body and deliver the message.
              toReturn = processBody();
              // Since we've delivered a message, decrement the number of pending
              // deliveries remaining.
              pendingDeliveries.getAndDecrement();
              break;
            default:
              close();
              AssertionError t = new AssertionError("Invalid state: " + state);
              listener.deframeFailed(t);
              throw t;
          }
        }
        if (toReturn == null) {
          checkEndOfStreamOrStalled();
        }
        return toReturn;
      } finally {
        inDelivery = false;
      }
    }

    @Override
    public void checkEndOfStreamOrStalled() {
      //TODO(ericgribkoff) If tests can be modified, make this private and not part of interface.
      Preconditions.checkState(!closed, "closed");
      if (closed) {
        return;
      }
      // TODO(ericgribkoff) Do we actually need a return value for this? No - if we stall or reach
      //   end of stream, call next() won't return anything - this is true in transport thread,
      //   maybe different in client thread?
      // TODO(ericgribkoff) handle this with return type - needs to be separate function?
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

      if (endOfStream && stalled) {
        boolean havePartialMessage = nextFrame != null && nextFrame.readableBytes() > 0;
        if (!havePartialMessage) {
          // TODO(ericgribkoff) Verify that it's ok to call endOfStream() here even though we may
          // return a message:
          //   in transport thread, this will execute immediately...not ok (closes listener, then we
          //     signal listener we have a new message with the return value below)
          //   in application thread, this should get queued on the transport thread and may or may
          //     run before we get the message to the client logic...also not ok
          listener.endOfStream();
          deliveryStalled = false;
          return;
        } else {
          // We've received the entire stream and have data available but we don't have
          // enough to read the next frame ... this is bad.
          close();
          RuntimeException t = Status.INTERNAL.withDescription(
              debugString + ": Encountered end-of-stream mid-frame").asRuntimeException();
          listener.deframeFailed(t);
          throw t;
        }
      }

      // If we're transitioning to the stalled state, notify the listener.
      // Always notify listener.
      boolean previouslyStalled = deliveryStalled;
      deliveryStalled = stalled;
      if (stalled) {
        // Always notify if stalled. Avoids race condition.
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
     */
    private void processHeader() {
      int type = nextFrame.readUnsignedByte();
      if ((type & RESERVED_MASK) != 0) {
        close();
        RuntimeException t = Status.INTERNAL.withDescription(
            debugString + ": Frame header malformed: reserved bits not zero")
            .asRuntimeException();
        listener.deframeFailed(t);
        throw t;
      }
      compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

      // Update the required length to include the length of the frame.
      requiredLength = nextFrame.readInt();
      if (requiredLength < 0 || requiredLength > maxInboundMessageSize) {
        close();
        RuntimeException t = Status.RESOURCE_EXHAUSTED.withDescription(
            String.format("%s: Frame size %d exceeds maximum: %d. ",
                debugString, requiredLength, maxInboundMessageSize)).asRuntimeException();
        listener.deframeFailed(t);
        throw t;
      }
      statsTraceCtx.inboundMessage();
      // Continue reading the frame body.
      state = State.BODY;
    }

    /**
     * Processes the GRPC message body, which depending on frame header flags may be compressed.
     */
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

    private InputStream getCompressedBody() {
      if (decompressor == Codec.Identity.NONE) {
        close();
        RuntimeException t = Status.INTERNAL.withDescription(
            debugString + ": Can't decode compressed frame as compression not configured.")
            .asRuntimeException();
        listener.deframeFailed(t);
        throw t;
      }

      try {
        // Enforce the maxMessageSize limit on the returned stream.
        InputStream unlimitedStream =
            decompressor.decompress(ReadableBuffers.openStream(nextFrame, true));
        return new SizeEnforcingInputStream(
            unlimitedStream, maxInboundMessageSize, statsTraceCtx, debugString);
      } catch (IOException e) {
        close();
        RuntimeException t = new RuntimeException(e);
        listener.deframeFailed(t);
        throw t;
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
