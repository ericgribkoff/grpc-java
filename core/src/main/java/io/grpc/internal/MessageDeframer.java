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
  }

  /**
   * A message producing object.
   */
  public interface MessageProducer {
    @Nullable
    InputStream next();

    interface Listener {
      /**
       * Called when the given number of bytes has been read from the input source of the deframer.
       * This is typically used to indicate to the underlying transport that more data can be
       * accepted.
       *
       * @param numBytes the number of bytes read from the deframer's input source.
       */
      void bytesRead(int numBytes);

      /**
       * Called when there are no complete messages remaining to be delivered and the transport has
       * called scheduleClose().
       *
       * @param hasPartialMessage whether there is an incomplete message queued
       */
      void messageProducerClosed(boolean hasPartialMessage);

      /**
       * Called when deframe fails. If invoked, this will be
       * the last call made to the listener (other than messageProducerAvailable(), which should be
       * a separate interface), as the deframing is in an unrecoverable state.
       */
      void deframeFailed(Throwable t);
    }
  }

  private enum State {
    HEADER, BODY
  }

  private final Listener listener;
  private final StatsTraceContext statsTraceCtx;
  private final String debugString;
  private final Producer producer;

  // TODO(ericgribkoff) Stronger documentation/checks that this is only safe to call before the
  // stream starts.
  private int maxInboundMessageSize;
  // TODO(ericgribkoff) Stronger documentation/checks that this is only called once and before
  // messages are processed.
  private Decompressor decompressor;
  // Set to true when request() is called. Used to trigger error when attempting to set
  // maxInboundMessageSize too late.
  private boolean requestCalled;
  // Set to true when deframe() is called. Used to trigger error when attempting to set decompressor
  // too late.
  private boolean deframeCalled;

  // unprocessed and pendingDeliveries both track pending work for the message producer, but
  // at different levels of granularity - unprocessed is made up of http2 data frames and
  // pendingDeliveries tracks the number of gRPC messages requested by the application and not yet
  // delivered.
  private CompositeReadableBuffer unprocessed = new CompositeReadableBuffer();
  private final AtomicInteger pendingDeliveries = new AtomicInteger();

  // This serves as an interrupt bit for the transport thread to schedule a close on the message
  // producer thread. This will only ever change from false to true.
  // From the point of view of the transport thread, the deframer is closed as soon as
  // closeScheduled is called. TODO: dangerous...can only schedule when stalled?
  private volatile boolean closeScheduled; // only written by transport thread
  private boolean stopDelivery; // only goes from false to true. only set when closeScheduled is set

  private boolean isClient;

  /**
   * Create a deframer.
   *
   * @param listener listener for deframer events.
   * @param decompressor the compression used if a compressed frame is encountered, with {@code
   *   NONE} meaning unsupported
   * @param maxMessageSize the maximum allowed size for received messages.
   * @param debugString a string that will appear on errors statuses
   */
  public MessageDeframer(Listener listener, MessageProducer.Listener producerListener,
      Decompressor decompressor, int maxMessageSize, StatsTraceContext statsTraceCtx,
      String debugString) {
    this.listener = Preconditions.checkNotNull(listener, "sink");
    this.decompressor = Preconditions.checkNotNull(decompressor, "decompressor");
    this.maxInboundMessageSize = maxMessageSize;
    this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
    this.debugString = debugString;
    this.producer = new Producer(checkNotNull(producerListener, "producerListener"));
  }

  public void setClient(boolean isClient) {
    this.isClient = isClient;
  }

  // Preconditions: request() has not yet been called (=> the stream has not yet started)
  void setMaxInboundMessageSize(int messageSize) {
    Preconditions.checkState(!requestCalled,
        "already requested messages, too late to set max inbound message size");
    maxInboundMessageSize = messageSize;
  }

  /**
   * Sets the decompressor available to use.  The message encoding for the stream comes later in
   * time, and thus will not be available at the time of construction.  This should only be set
   * once, since the compression codec cannot change after the headers have been sent.
   *
   * @param decompressor the decompressing wrapper.
   */
  // Preconditions: deframe() has not yet been called
  public void setDecompressor(Decompressor decompressor) {
    if (!isClient) {
      System.out.println("decompressor set on server");
    }
    // TODO(ericgribkoff) Re-enable after investigating flaky compression test
    //Preconditions
    //    .checkState(!deframeCalled, "already started to deframe, too late to set decompressor");
    this.decompressor = checkNotNull(decompressor, "Can't pass an empty decompressor");
  }

  /**
   * Requests up to the given number of messages from the call to be delivered via {@link
   * Listener#messageProducerAvailable(MessageProducer)}. No additional messages will be delivered.
   *
   * <p>If {@code scheduleClose()} has already been called, this method will have no effect.
   *
   * @param numMessages the requested number of messages to be delivered to the listener.
   */
  // Preconditions: None
  // Postconditions: pendingDeliveries_n = pendingDeliveries_(n-1) + numMessages
  // messageProducerAvailable triggered on listener
  public void request(int numMessages) {
    // TODO(ericgribkoff) Precondition - stopDelivery = false?
    if (isClient) {
      System.out.println("requested on client side");
    }
    Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
    requestCalled = true;
    pendingDeliveries.getAndAdd(numMessages);
    listener.messageProducerAvailable(producer);
  }

  /**
   * Adds the given data to this deframer and attempts delivery to the listener.
   *
   * @param data the raw data read from the remote endpoint. Must be non-null.
   * @throws IllegalStateException if already scheduled to close or if this method has previously
   *     been called with {@code endOfStream=true}.
   */
  // Preconditions: not scheduled to close
  // Postconditions: data will be added to unprocessed
  //   triggers call to listener.messageProducerAvailable
  public void deframe(ReadableBuffer data) {
    Preconditions.checkNotNull(data, "data");
    deframeCalled = true;
    boolean needToCloseData = true;
    try {
      Preconditions.checkState(!isScheduledToClose(), "MessageDeframer already scheduled to close");

      unprocessed.addBuffer(data);
      needToCloseData = false;

      // Indicate that all of the data for this stream has been received.
      listener.messageProducerAvailable(producer);
    } finally {
      if (needToCloseData) {
        data.close();
      }
    }
  }

  /**
   * Schedule close in the client thread once any buffered messages are processed.
   */
  // Preconditions: not already scheduled to close
  // Postconditions: closeScheduled = true
  //   triggers call to listener.messageProducerAvailable
  // TODO(ericgribkoff) Remove arg and separate into scheduleCloseWhenDone() and
  // scheduleImmediateClose()?
  public void scheduleClose(boolean stopDelivery) {
    // TODO(ericgribkoff) This will fail.
    Preconditions.checkState(!closeScheduled, "close already scheduled");
    this.stopDelivery = stopDelivery;
    closeScheduled = true;
    // Make sure that the message producer will see the scheduled close.
    listener.messageProducerAvailable(producer);
  }

  /**
   * Indicates whether or not this deframer has been closed.
   */
  public boolean isScheduledToClose() {
    return closeScheduled;
  }

  private class Producer implements MessageProducer {
    private final MessageProducer.Listener producerListener;
    private State state = State.HEADER;
    private int requiredLength = HEADER_LENGTH;
    private boolean compressedFlag;
    private boolean inDelivery = false;
    private boolean closed;
    // Indicates a deframing error occurred. When this is set to true, deframeFailed() must be
    // called on the listener and no further callbacks may be issued by the producer.
    private boolean deframeFailed;

    private CompositeReadableBuffer nextFrame;

    private Producer(MessageProducer.Listener producerListener) {
      this.producerListener = producerListener;
    }

    private void closeAndNotify() {
      Preconditions.checkState(!closed, "producer already closed");
      boolean hadPartialMessage = nextFrame != null && nextFrame.readableBytes() > 0;
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
      if (stopDelivery) {
        // We aborted based on the transport's instructions, so hadPartialMessage is irrelevant
        producerListener.messageProducerClosed(false);
      } else {
        producerListener.messageProducerClosed(hadPartialMessage);
      }
    }

    /**
     * Reads and delivers as many messages to the listener as possible.
     */
    @Override
    public InputStream next() {
      if (deframeFailed) {
        return null;
      }
      if (closed) {
        // May be called after close by previously scheduled messageProducerAvailable() calls
        return null;
      }
      if (inDelivery) {
        return null;
      }
      inDelivery = true;

      try {
        // Process the uncompressed bytes.
        while (!stopDelivery && (pendingDeliveries.get() > 0 && readRequiredBytes())) {
          switch (state) {
            case HEADER:
              processHeader();
              if (deframeFailed) {
                // Error occurred
                return null;
              }
              break;
            case BODY:
              // Read the body and deliver the message.
              InputStream toReturn = processBody();
              if (deframeFailed) {
                // Error occurred
                return null;
              }
              if (isClient) {
                System.out.println("delivering message to client");
              }
              // Since we've delivered a message, decrement the number of pending
              // deliveries remaining.
              pendingDeliveries.getAndDecrement();
              return toReturn;
            default:
              deframeFailed = true;
              AssertionError t = new AssertionError("Invalid state: " + state);
              producerListener.deframeFailed(t);
              producerListener.messageProducerClosed(true);
              return null;
          }
        }

        boolean closeNow = closeScheduled;
        /*
         * We are stalled when there are no more bytes to process. This allows delivering errors as
         * soon as the buffered input has been consumed, independent of whether the application has
         * requested another message.  At this point in the function, either all frames have been
         * delivered, or unprocessed is empty.  If there is a partial message, it will be inside
         * next frame and not in unprocessed.  If there is extra data but no pending deliveries, it
         * will be in unprocessed.
         */
        boolean stalled = unprocessed.readableBytes() == 0;

        if (stopDelivery || (stalled && closeNow)) {
          closeAndNotify();
        }
        return null;
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
          producerListener.bytesRead(totalBytesRead);
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
        deframeFailed = true;
        RuntimeException t = Status.INTERNAL.withDescription(
            debugString + ": Frame header malformed: reserved bits not zero")
            .asRuntimeException();
        producerListener.deframeFailed(t);
        producerListener.messageProducerClosed(true);
        return;
      }
      compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

      // Update the required length to include the length of the frame.
      requiredLength = nextFrame.readInt();
      if (requiredLength < 0 || requiredLength > maxInboundMessageSize) {
        deframeFailed = true;
        RuntimeException t = Status.RESOURCE_EXHAUSTED.withDescription(
            String.format("%s: Frame size %d exceeds maximum: %d. ",
                debugString, requiredLength, maxInboundMessageSize)).asRuntimeException();
        producerListener.deframeFailed(t);
        producerListener.messageProducerClosed(true);
        return;
      }
      statsTraceCtx.inboundMessage();
      // Continue reading the frame body.
      state = State.BODY;
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
        deframeFailed = true;
        RuntimeException t = Status.INTERNAL.withDescription(
            debugString + ": Can't decode compressed frame as compression not configured.")
            .asRuntimeException();
        producerListener.deframeFailed(t);
        producerListener.messageProducerClosed(true);
        return null;
      }

      try {
        // Enforce the maxMessageSize limit on the returned stream.
        InputStream unlimitedStream =
            decompressor.decompress(ReadableBuffers.openStream(nextFrame, true));
        return new SizeEnforcingInputStream(
            unlimitedStream, maxInboundMessageSize, statsTraceCtx, debugString);
      } catch (IOException e) {
        deframeFailed = true;
        RuntimeException t = new RuntimeException(e);
        producerListener.deframeFailed(t);
        producerListener.messageProducerClosed(true);
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
