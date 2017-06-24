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

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.Status;
import javax.annotation.Nullable;

/**
 * Abstract base class for {@link ServerStream} implementations. Extending classes only need to
 * implement {@link #transportState()} and {@link #abstractServerStreamSink()}. Must only be called
 * from the sending application thread.
 */
public abstract class AbstractServerStream extends AbstractStream
    implements ServerStream, MessageFramer.Sink {
  /**
   * A sink for outbound operations, separated from the stream simply to avoid name
   * collisions/confusion. Only called from application thread.
   */
  protected interface Sink {
    /**
     * Sends response headers to the remote end point.
     *
     * @param headers the headers to be sent to client.
     */
    void writeHeaders(Metadata headers);

    /**
     * Sends an outbound frame to the remote end point.
     *
     * @param frame a buffer containing the chunk of data to be sent.
     * @param flush {@code true} if more data may not be arriving soon
     */
    void writeFrame(@Nullable WritableBuffer frame, boolean flush);

    /**
     * Sends trailers to the remote end point. This call implies end of stream.
     *
     * @param trailers metadata to be sent to the end point
     * @param headersSent {@code true} if response headers have already been sent.
     */
    void writeTrailers(Metadata trailers, boolean headersSent);

    /**
     * Requests up to the given number of messages from the call to be delivered. This should end up
     * triggering {@link TransportState#requestMessagesFromDeframer(int)} on the transport thread.
     */
    void request(int numMessages);

    /**
     * Tears down the stream, typically in the event of a timeout. This method may be called
     * multiple times and from any thread.
     *
     * <p>This is a clone of {@link ServerStream#cancel(Status)}.
     */
    void cancel(Status status);
  }

  private final MessageFramer framer;
  private final StatsTraceContext statsTraceCtx;
  private boolean outboundClosed;
  private boolean headersSent;

  protected AbstractServerStream(WritableBufferAllocator bufferAllocator,
      StatsTraceContext statsTraceCtx) {
    this.statsTraceCtx = Preconditions.checkNotNull(statsTraceCtx, "statsTraceCtx");
    framer = new MessageFramer(this, bufferAllocator, statsTraceCtx);
  }

  @Override
  protected abstract TransportState transportState();

  /**
   * Sink for transport to be called to perform outbound operations. Each stream must have its own
   * unique sink.
   */
  protected abstract Sink abstractServerStreamSink();

  @Override
  protected final MessageFramer framer() {
    return framer;
  }

  @Override
  public final void request(int numMessages) {
    abstractServerStreamSink().request(numMessages);
  }

  @Override
  public final void writeHeaders(Metadata headers) {
    Preconditions.checkNotNull(headers, "headers");

    headersSent = true;
    abstractServerStreamSink().writeHeaders(headers);
  }

  @Override
  public final void deliverFrame(WritableBuffer frame, boolean endOfStream, boolean flush) {
    // Since endOfStream is triggered by the sending of trailers, avoid flush here and just flush
    // after the trailers.
    abstractServerStreamSink().writeFrame(frame, endOfStream ? false : flush);
  }

  @Override
  public final void close(Status status, Metadata trailers) {
    Preconditions.checkNotNull(status, "status");
    Preconditions.checkNotNull(trailers, "trailers");
    if (!outboundClosed) {
      outboundClosed = true;
      statsTraceCtx.streamClosed(status);
      endOfMessages();
      addStatusToTrailers(trailers, status);
      abstractServerStreamSink().writeTrailers(trailers, headersSent);
    }
  }

  private void addStatusToTrailers(Metadata trailers, Status status) {
    trailers.discardAll(InternalStatus.CODE_KEY);
    trailers.discardAll(InternalStatus.MESSAGE_KEY);
    trailers.put(InternalStatus.CODE_KEY, status);
    if (status.getDescription() != null) {
      trailers.put(InternalStatus.MESSAGE_KEY, status.getDescription());
    }
  }

  @Override
  public final void cancel(Status status) {
    abstractServerStreamSink().cancel(status);
  }

  @Override
  public final boolean isReady() {
    return super.isReady();
  }

  @Override public Attributes getAttributes() {
    return Attributes.EMPTY;
  }

  @Override
  public String getAuthority() {
    return null;
  }

  @Override
  public final void setListener(ServerStreamListener serverStreamListener) {
    transportState().setListener(serverStreamListener);
  }

  @Override
  public StatsTraceContext statsTraceContext() {
    return statsTraceCtx;
  }

  /** This should only called from the transport thread. */
  protected abstract static class TransportState extends AbstractStream.TransportState {
    /** Whether listener.closed() has been called. */
    private boolean listenerClosed;
    private ServerStreamListener listener;
    private final StatsTraceContext statsTraceCtx;

    private boolean endOfStream = false;
    private Status statusToReport = null;
    private boolean deframerClosed = false;

    protected TransportState(int maxMessageSize, StatsTraceContext statsTraceCtx) {
      super(maxMessageSize, statsTraceCtx, false);
      this.statsTraceCtx = Preconditions.checkNotNull(statsTraceCtx, "statsTraceCtx");
    }

    /**
     * Sets the listener to receive notifications. Must be called in the context of the transport
     * thread.
     */
    public final void setListener(ServerStreamListener listener) {
      Preconditions.checkState(this.listener == null, "setListener should be called only once");
      this.listener = Preconditions.checkNotNull(listener, "listener");
    }

    @Override
    public final void onStreamAllocated() {
      super.onStreamAllocated();
    }

    //    @Override
    //    public void deliveryStalled() {}
    //
    //    @Override
    //    public void endOfStream() {
    //      closeDeframer();
    //      listener().halfClosed();
    //    }

    @Override
    protected ServerStreamListener listener() {
      return listener;
    }

    /**
     * Called in the transport thread to process the content of an inbound DATA frame from the
     * client.
     *
     * @param frame the inbound HTTP/2 DATA frame. If this buffer is not used immediately, it must
     *              be retained.
     * @param endOfStream {@code true} if no more data will be received on the stream.
     */
    public void inboundDataReceived(ReadableBuffer frame, boolean endOfStream) {
      // Deframe the message. If a failure occurs, deframeFailed will be called.
      //      deframe(frame, endOfStream);
      deframe(frame, false);
      if (endOfStream) {
        this.endOfStream = true;
        closeDeframer(false);
      }
    }

    /**
     * Notifies failure to the listener of the stream. The transport is responsible for notifying
     * the client of the failure independent of this method.
     *
     * <p>Unlike {@link #close(Status, Metadata)}, this method is only called from the
     * transport. The transport should use this method instead of {@code close(Status)} for internal
     * errors to prevent exposing unexpected states and exceptions to the application.
     *
     * @param status the error status. Must not be {@link Status#OK}.
     */
    public final void transportReportStatus(Status status) {
      Preconditions.checkArgument(!status.isOk(), "status must not be OK");
      // Only close deframer here if we haven't already in response to "end of stream"
      if (deframerClosed) {
        closeListener(status);
      } else {
        statusToReport = status;
        closeDeframer(true);
      }
    }

    /**
     * Indicates the stream is considered completely closed and there is no further opportunity for
     * error. It calls the listener's {@code closed()} if it was not already done by {@link
     * #transportReportStatus}.
     */
    public void complete() {
      if (deframerClosed) {
        closeListener(Status.OK);
      } else {
        statusToReport = Status.OK;
        closeDeframer(true);
      }
    }

    @Override
    public void deframerClosed() {
      System.out.println("deframerClosed on server");
      deframerClosed = true;
      if (endOfStream) {
        listener.halfClosed();
      }
      if (statusToReport != null) {
        closeListener(statusToReport);
      }
    }

    /**
     * Closes the listener if not previously closed and frees resources.
     */
    private void closeListener(Status newStatus) {
      if (!listenerClosed) {
        // If status is OK, close() is guaranteed to be called which should decide the final status
        if (!newStatus.isOk()) {
          statsTraceCtx.streamClosed(newStatus);
        }
        listenerClosed = true;
        onStreamDeallocated();
        listener().closed(newStatus);
      }
    }
  }
}
