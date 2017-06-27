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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Compressor;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * The abstract base class for {@link ClientStream} implementations. Extending classes only need to
 * implement {@link #transportState()} and {@link #abstractClientStreamSink()}. Must only be called
 * from the sending application thread.
 */
public abstract class AbstractClientStream extends AbstractStream
    implements ClientStream, MessageFramer.Sink {

  private static final Logger log = Logger.getLogger(AbstractClientStream.class.getName());

  /**
   * A sink for outbound operations, separated from the stream simply to avoid name
   * collisions/confusion. Only called from application thread.
   */
  protected interface Sink {
    /** 
     * Sends the request headers to the remote end point.
     *
     * @param metadata the metadata to be sent
     * @param payload the payload needs to be sent in the headers if not null. Should only be used
     *     when sending an unary GET request
     */
    void writeHeaders(Metadata metadata, @Nullable byte[] payload);

    /**
     * Sends an outbound frame to the remote end point.
     *
     * @param frame a buffer containing the chunk of data to be sent, or {@code null} if {@code
     *     endOfStream} with no data to send
     * @param endOfStream {@code true} if this is the last frame; {@code flush} is guaranteed to be
     *     {@code true} if this is {@code true}
     * @param flush {@code true} if more data may not be arriving soon
     */
    void writeFrame(@Nullable WritableBuffer frame, boolean endOfStream, boolean flush);

    /**
     * Requests up to the given number of messages from the call to be delivered to the client. This
     * should end up triggering {@link TransportState#requestMessagesFromDeframer(int)} on the
     * transport thread.
     */
    void request(int numMessages);

    /**
     * Tears down the stream, typically in the event of a timeout. This method may be called
     * multiple times and from any thread.
     *
     * <p>This is a clone of {@link ClientStream#cancel(Status)};
     * {@link AbstractClientStream#cancel} delegates to this method.
     */
    void cancel(Status status);
  }

  private final Framer framer;
  private boolean useGet;
  private Metadata headers;
  private boolean outboundClosed;
  /**
   * Whether cancel() has been called. This is not strictly necessary, but removes the delay between
   * cancel() being called and isReady() beginning to return false, since cancel is commonly
   * processed asynchronously.
   */
  private volatile boolean cancelled;

  protected AbstractClientStream(WritableBufferAllocator bufferAllocator,
      StatsTraceContext statsTraceCtx, Metadata headers, boolean useGet) {
    Preconditions.checkNotNull(headers, "headers");
    this.useGet = useGet;
    if (!useGet) {
      framer = new MessageFramer(this, bufferAllocator, statsTraceCtx);
      this.headers = headers;
    } else {
      framer = new GetFramer(headers, statsTraceCtx);
    }
  }

  @Override
  public void setMaxOutboundMessageSize(int maxSize) {
    framer.setMaxOutboundMessageSize(maxSize);
  }

  @Override
  public void setMaxInboundMessageSize(int maxSize) {
    transportState().setMaxInboundMessageSize(maxSize);
  }

  /** {@inheritDoc} */
  @Override
  protected abstract TransportState transportState();

  @Override
  public final void start(ClientStreamListener listener) {
    transportState().setListener(listener);
    if (!useGet) {
      abstractClientStreamSink().writeHeaders(headers, null);
      headers = null;
    }
  }

  /**
   * Sink for transport to be called to perform outbound operations. Each stream must have its own
   * unique sink.
   */
  protected abstract Sink abstractClientStreamSink();

  @Override
  protected final Framer framer() {
    return framer;
  }

  @Override
  public final void request(int numMessages) {
    abstractClientStreamSink().request(numMessages);
  }

  @Override
  public final void deliverFrame(WritableBuffer frame, boolean endOfStream, boolean flush) {
    Preconditions.checkArgument(frame != null || endOfStream, "null frame before EOS");
    abstractClientStreamSink().writeFrame(frame, endOfStream, flush);
  }

  @Override
  public final void halfClose() {
    if (!outboundClosed) {
      outboundClosed = true;
      endOfMessages();
    }
  }

  @Override
  public final void cancel(Status reason) {
    Preconditions.checkArgument(!reason.isOk(), "Should not cancel with OK status");
    cancelled = true;
    abstractClientStreamSink().cancel(reason);
  }

  @Override
  public final boolean isReady() {
    return super.isReady() && !cancelled;
  }

  /** This should only called from the transport thread. */
  protected abstract static class TransportState extends AbstractStream.TransportState {
    /** Whether listener.closed() has been called. */
    private final StatsTraceContext statsTraceCtx;
    private boolean listenerClosed;
    private ClientStreamListener listener;

    protected Runnable deframerClosedTask;

    /**
     * Whether the stream is closed from the transport's perspective. This can differ from {@link
     * #listenerClosed} because there may still be messages buffered to deliver to the application.
     */
    private boolean statusReported;

    protected TransportState(int maxMessageSize, StatsTraceContext statsTraceCtx) {
      super(maxMessageSize, statsTraceCtx);
      this.statsTraceCtx = Preconditions.checkNotNull(statsTraceCtx, "statsTraceCtx");
      client = true;
    }

    @VisibleForTesting
    public final void setListener(ClientStreamListener listener) {
      Preconditions.checkState(this.listener == null, "Already called setListener");
      this.listener = Preconditions.checkNotNull(listener, "listener");
    }

    @Override
    protected final ClientStreamListener listener() {
      return listener;
    }

    protected void runDeframerClosedTask() {
      if (deframerClosedTask != null) {
        deframerClosedTask.run();
      }
    }

    /**
     * Called by transport implementations when they receive headers.
     *
     * @param headers the parsed headers
     */
    protected void inboundHeadersReceived(Metadata headers) {
      Preconditions.checkState(!statusReported, "Received headers on closed stream");
      statsTraceCtx.clientInboundHeaders();
      listener().headersRead(headers);
    }

    /**
     * Processes the contents of a received data frame from the server.
     *
     * @param frame the received data frame. Its ownership is transferred to this method.
     */
    protected void inboundDataReceived(ReadableBuffer frame) {
      Preconditions.checkNotNull(frame, "frame");
      boolean needToCloseFrame = true;
      try {
        if (statusReported) {
          log.log(Level.INFO, "Received data on closed stream");
          return;
        }

        needToCloseFrame = false;
        deframe(frame);
      } finally {
        if (needToCloseFrame) {
          frame.close();
        }
      }
    }

    /**
     * Processes the trailers and status from the server.
     *
     * @param trailers the received trailers
     * @param status the status extracted from the trailers
     */
    protected void inboundTrailersReceived(Metadata trailers, Status status) {
      Preconditions.checkNotNull(status, "status");
      Preconditions.checkNotNull(trailers, "trailers");
      if (statusReported) {
        log.log(Level.INFO, "Received trailers on closed stream:\n {1}\n {2}",
            new Object[]{status, trailers});
        return;
      }
      transportReportStatus(status, false, trailers);
    }

    /**
     * Report stream closure with status to the application layer if not already reported. This
     * method must be called from the transport thread.
     *
     * @param status the new status to set
     * @param stopDelivery if {@code true}, interrupts any further delivery of inbound messages that
     *        may already be queued up in the deframer. If {@code false}, the listener will be
     *        notified immediately after all currently completed messages in the deframer have been
     *        delivered to the application.
     * @param trailers new instance of {@code Trailers}, either empty or those returned by the
     *        server
     */
    public final void transportReportStatus(final Status status, boolean stopDelivery,
        final Metadata trailers) {
      System.out.println("transportReportStatus with statusReported=" + statusReported);
      Preconditions.checkNotNull(status, "status");
      Preconditions.checkNotNull(trailers, "trailers");
      // If stopDelivery, we continue in case previous invocation is waiting for stall
      if (statusReported && !stopDelivery) {
        return;
      }
      statusReported = true;
      onStreamDeallocated();

      deframerClosedTask =
          new Runnable() {
            @Override
            public void run() {
              if (!listenerClosed) {
                listenerClosed = true;
                statsTraceCtx.streamClosed(status);
                listener().closed(status, trailers);
              }
            }
          };
      closeDeframer(stopDelivery);
    }
  }

  private class GetFramer implements Framer {
    private Metadata headers;
    private boolean closed;
    private final StatsTraceContext statsTraceCtx;
    private byte[] payload;

    public GetFramer(Metadata headers, StatsTraceContext statsTraceCtx) {
      this.headers = Preconditions.checkNotNull(headers, "headers");
      this.statsTraceCtx = Preconditions.checkNotNull(statsTraceCtx, "statsTraceCtx");
    }

    @Override
    public void writePayload(InputStream message) {
      Preconditions.checkState(payload == null, "writePayload should not be called multiple times");
      try {
        payload = IoUtils.toByteArray(message);
      } catch (java.io.IOException ex) {
        throw new RuntimeException(ex);
      }
      statsTraceCtx.outboundMessage();
      statsTraceCtx.outboundUncompressedSize(payload.length);
      // NB(zhangkun83): this is not accurate, because the underlying transport will probably encode
      // it using e.g., base64.  However, we are not supposed to know such detail here.
      //
      // We don't want to move this line to where the encoding happens either, because we'd better
      // contain the message stats reporting in Framer as suggested in StatsTraceContext.
      // Scattering the reporting sites increases the risk of mis-counting or double-counting.
      //
      // Because the payload is usually very small, people shouldn't care about the size difference
      // caused by encoding.
      statsTraceCtx.outboundWireSize(payload.length);
    }

    @Override
    public void flush() {}

    @Override
    public boolean isClosed() {
      return closed;
    }

    /** Closes, with flush. */
    @Override
    public void close() {
      closed = true;
      Preconditions.checkState(payload != null,
          "Lack of request message. GET request is only supported for unary requests");
      abstractClientStreamSink().writeHeaders(headers, payload);
      payload = null;
      headers = null;
    }

    /** Closes, without flush. */
    @Override
    public void dispose() {
      closed = true;
      payload = null;
      headers = null;
    }

    // Compression is not supported for GET encoding.
    @Override
    public Framer setMessageCompression(boolean enable) {
      return this;
    }

    @Override
    public Framer setCompressor(Compressor compressor) {
      return this;
    }

    // TODO(zsurocking): support this
    @Override
    public void setMaxOutboundMessageSize(int maxSize) {}
  }
}
