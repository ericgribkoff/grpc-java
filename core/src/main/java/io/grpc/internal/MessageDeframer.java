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
import java.util.zip.DataFormatException;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Deframer for GRPC frames.
 *
 * <p>This class is not thread-safe. Unless otherwise stated, all calls to public methods should be
 * made in the deframing thread.
 */
@NotThreadSafe
public class MessageDeframer implements Closeable, Deframer {
  private static final int HEADER_LENGTH = 5;
  private static final int COMPRESSED_FLAG_MASK = 1;
  private static final int RESERVED_MASK = 0xFE;

  /**
   * A listener of deframing events. These methods will be invoked from the deframing thread.
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
     * @param producer single message producer wrapping the message.
     */
    void messagesAvailable(StreamListener.MessageProducer producer);

    /**
     * Called when the deframer closes.
     *
     * @param hasPartialMessage whether the deframer contained an incomplete message at closing.
     */
    void deframerClosed(boolean hasPartialMessage);

    /**
     * Called when a {@link #deframe(ReadableBuffer)} operation failed.
     *
     * @param cause the actual failure
     */
    void deframeFailed(Throwable cause);
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
  private int requiredLength = HEADER_LENGTH; // TODO - Does int capture max size?
  private boolean compressedFlag;
  private CompositeReadableBuffer nextFrame;
  private CompositeBuffer unprocessed = new CompositeReadableBuffer();
  private long pendingDeliveries;
  private boolean inDelivery = false;

  private boolean closeWhenComplete = false;
  private volatile boolean stopDelivery = false;

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

  private GZipInflatingBuffer gzipInflater;

  public void setGZipInflater(GZipInflatingBuffer gzipInflater) {
    this.gzipInflater = gzipInflater;
  }

  @Override
  public void setMaxInboundMessageSize(int messageSize) {
    maxInboundMessageSize = messageSize;
  }

  @Override
  public void setDecompressor(Decompressor decompressor) {
    this.decompressor = checkNotNull(decompressor, "Can't pass an empty decompressor");
  }

  @Override
  public void request(int numMessages) {
    Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
    if (isClosed()) {
      return;
    }
    pendingDeliveries += numMessages;
    deliver();
  }

  @Override
  public void deframe(ReadableBuffer data) {
    Preconditions.checkNotNull(data, "data");
    boolean needToCloseData = true;
    try {
      if (!isClosedOrScheduledToClose()) {
        if (gzipInflater != null) {
          gzipInflater.addCompressedBytes(data);
        } else {
          unprocessed.addBuffer(data);
        }
        needToCloseData = false;

        deliver();
      }
    } finally {
      if (needToCloseData) {
        data.close();
      }
    }
  }

  @Override
  public void closeWhenComplete() {
    // TODO unprocessed == null doesn't make the most sense to check with stream compression
    if (unprocessed == null) {
      return;
    }
    if (isStalled()) {
      close();
    } else {
      closeWhenComplete = true;
    }
  }

  /**
   * Sets a flag to interrupt delivery of any currently queued messages. This may be invoked outside
   * of the deframing thread, and must be followed by a call to {@link #close()} in the deframing
   * thread. Without a subsequent call to {@link #close()}, the deframer may hang waiting for
   * additional messages before noticing that the {@code stopDelivery} flag has been set.
   */
  void stopDelivery() {
    stopDelivery = true;
  }

  @Override
  public void close() {
    System.out.println("close() invoked");
    new Exception().printStackTrace(System.out);
    if (isClosed()) {
      return;
    }
    boolean hasPartialMessage;
    if (gzipInflater != null) {
      hasPartialMessage = (nextFrame != null && nextFrame.readableBytes() > 0) || gzipInflater.hasPartialData();
    } else {
      hasPartialMessage = nextFrame != null && nextFrame.readableBytes() > 0;
    }
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
    listener.deframerClosed(hasPartialMessage);
  }

  /**
   * Indicates whether or not this deframer has been closed.
   */
  public boolean isClosed() {
    return unprocessed == null;
  }

  /** Returns true if this deframer has already been closed or scheduled to close. */
  private boolean isClosedOrScheduledToClose() {
    return isClosed() || closeWhenComplete;
  }

  private boolean isStalled() {
    if (gzipInflater != null) {
      return gzipInflater.isStalled();
    } else {
      return unprocessed.readableBytes() == 0;
    }
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
      while (!stopDelivery && pendingDeliveries > 0 && readRequiredBytes()) {
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

      if (stopDelivery) {
        close();
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
      if (closeWhenComplete && isStalled()) {
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
  // TODO handle this with compressed data
  private boolean readRequiredBytes() {
    int totalBytesRead = 0;
    try {
      // TODO nextFrame essentially "moves" into GZipInflatingBuffer for a compressed stream
      if (nextFrame == null) {
        nextFrame = new CompositeReadableBuffer();
      }

      // Read until the buffer contains all the required bytes.
      int missingBytes;
      while ((missingBytes = requiredLength - nextFrame.readableBytes()) > 0) {
        if (gzipInflater != null) {
          // TODO how do we know if we are making progress...We know we are making progress if the
          // inflater outputs some uncompressed bytes. This does *not* mean that it read more
          // compressed bytes, AFAICT.
          // We do not need to loop here on the chance more data was simultaneously written to the
          // gzipInflater - this can't happen (single-threaded) and also would trigger another call
          // to deliver, which would pick up the data. Here we just need to know if the block is
          // ready.
          // even if we are or are not ready, the inflater may have consumed more compressed bytes,
          // so return these to flow control
          try {
            int uncompressedBytesRead = gzipInflater.readUncompressedBytes(missingBytes, nextFrame);
            int bytesRead = gzipInflater.getAndResetCompressedBytesConsumed();
            System.out.println("DEFRAMER: bytesRead = " + bytesRead);
            totalBytesRead += bytesRead;
            if (uncompressedBytesRead == 0) {
              return false;
            }
          } catch (IOException e) {
            // TODO handle properly (just re-throw?)
            System.out.println("Gzip exception");
            e.printStackTrace(System.out);
            throw new RuntimeException(e);
          } catch (DataFormatException e) {
            // TODO handle properly (just re-throw?)
            System.out.println("Data format exception");
            e.printStackTrace(System.out);
            throw new RuntimeException(e);
          }

          //          int compressedBytesRead = gzipInflater.uncompressedBytesReady(missingBytes);
          //          if (compressedBytesRead > 0) {
          //            totalBytesRead += 0;
          //          }

          //if (gzipInflater.uncompressedBytesReady(missingBytes)) {
          //  int compressedBytesRead = gzipInflater.readUncompressedBytes(missingBytes, nextFrame);
          //  totalBytesRead += compressedBytesRead;
          //} else {
          //  return false;
          //}
        } else {
          if (unprocessed.readableBytes() == 0) {
            // No more data is available.
            return false;
          }
          int toRead = Math.min(missingBytes, unprocessed.readableBytes());
          totalBytesRead += toRead;
          nextFrame.addBuffer(unprocessed.readBytes(toRead));
        }
      }
      System.out.println("readRequiredBytes complete! (" + requiredLength + " total needed)");
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
    listener.messagesAvailable(new SingleMessageProducer(stream));

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

  private static class SingleMessageProducer implements StreamListener.MessageProducer {
    private InputStream message;

    private SingleMessageProducer(InputStream message) {
      this.message = message;
    }

    @Nullable
    @Override
    public InputStream next() {
      InputStream messageToReturn = message;
      message = null;
      return messageToReturn;
    }
  }
}
