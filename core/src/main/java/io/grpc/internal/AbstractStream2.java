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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.Decompressor;
import io.grpc.internal.MessageDeframer.MessageProducer;
import java.io.InputStream;
import javax.annotation.concurrent.GuardedBy;

/**
 * The stream and stream state as used by the application. Must only be called from the sending
 * application thread.
 */
public abstract class AbstractStream2 implements Stream {
  /** The framer to use for sending messages. */
  protected abstract Framer framer();

  /**
   * Obtain the transport state corresponding to this stream. Each stream must have its own unique
   * transport state.
   */
  protected abstract TransportState transportState();

  @Override
  public final void setMessageCompression(boolean enable) {
    framer().setMessageCompression(enable);
  }

  @Override
  public final void writeMessage(InputStream message) {
    checkNotNull(message, "message");
    if (!framer().isClosed()) {
      framer().writePayload(message);
    }
  }

  @Override
  public final void flush() {
    if (!framer().isClosed()) {
      framer().flush();
    }
  }

  /**
   * Closes the underlying framer. Should be called when the outgoing stream is gracefully closed
   * (half closure on client; closure on server).
   */
  protected final void endOfMessages() {
    framer().close();
  }

  @Override
  public final void setCompressor(Compressor compressor) {
    framer().setCompressor(checkNotNull(compressor, "compressor"));
  }

  @Override
  public final void setDecompressor(Decompressor decompressor) {
    transportState().setDecompressor(checkNotNull(decompressor, "decompressor"));
  }

  @Override
  public boolean isReady() {
    if (framer().isClosed()) {
      return false;
    }
    return transportState().isReady();
  }

  /**
   * Event handler to be called by the subclass when a number of bytes are being queued for sending
   * to the remote endpoint.
   *
   * @param numBytes the number of bytes being sent.
   */
  protected final void onSendingBytes(int numBytes) {
    transportState().onSendingBytes(numBytes);
  }

  /**
   * Stream state as used by the transport. {@code MessageProducer.Listener} methods will be called
   * from the deframing thread. Other methods this should only be called from the transport thread
   * (except for private interactions with {@code AbstractStream2}).
   */
  public abstract static class TransportState implements MessageDeframer.Listener,
      MessageProducer.Listener {
    /**
     * The default number of queued bytes for a given stream, below which
     * {@link StreamListener#onReady()} will be called.
     */
    @VisibleForTesting
    public static final int DEFAULT_ONREADY_THRESHOLD = 32 * 1024;

    private final MessageDeframer deframer;
    private final Object onReadyLock = new Object();
    private final StatsTraceContext statsTraceCtx;
    /**
     * The number of bytes currently queued, waiting to be sent. When this falls below
     * DEFAULT_ONREADY_THRESHOLD, {@link StreamListener#onReady()} will be called.
     */
    @GuardedBy("onReadyLock")
    private int numSentBytesQueued;
    /**
     * Indicates the stream has been created on the connection. This implies that the stream is no
     * longer limited by MAX_CONCURRENT_STREAMS.
     */
    @GuardedBy("onReadyLock")
    private boolean allocated;
    /**
     * Indicates that the stream no longer exists for the transport. Implies that the application
     * should be discouraged from sending, because doing so would have no effect.
     */
    @GuardedBy("onReadyLock")
    private boolean deallocated;

    // TODO(ericgribkoff) Remove
    protected boolean isClient() {
      return false;
    }

    protected TransportState(int maxMessageSize, StatsTraceContext statsTraceCtx) {
      this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
      deframer = new MessageDeframer(
          this, this, Codec.Identity.NONE, maxMessageSize, statsTraceCtx,
          getClass().getName(), isClient());
    }

    final void setMaxInboundMessageSize(int maxSize) {
      deframer.setMaxInboundMessageSize(maxSize);
    }

    /**
     * Override this method to provide a stream listener.
     */
    protected abstract StreamListener listener();

    @Override
    public void messageProducerAvailable(MessageProducer mp) {
      // TODO(ericgribkoff) listener() can return null here, at least for ServerStream via
      //   setListener() - this occurs in io.grpc.internal.ServerTransportListener.streamCreated(),
      //   as the stream listener initialization hits request before it's actually added to the
      //   stream. This should either be avoided or this null-check must remain.
      if (listener() != null) {
        listener().messageProducerAvailable(mp);
      }
    }

    /**
     * Schedule the deframer to close.
     */
    protected final void scheduleDeframerClose() {
      deframer.scheduleClose();
    }

    /**
     * Indicates whether the deframer is scheduled to close.
     */
    protected final boolean isDeframerScheduledToClose() {
      return deframer.isScheduledToClose();
    }

    /**
     * Called to parse a received frame and attempt delivery of any completed
     * messages. Must be called from the transport thread.
     */
    protected final void deframe(ReadableBuffer frame, boolean endOfStream) {
      if (isDeframerScheduledToClose()) {
        frame.close();
        return;
      }
      try {
        deframer.deframe(frame, endOfStream);
      } catch (Throwable t) {
        // The deframer will not intentionally throw an exception but instead call deframeFailed
        // directly. This captures other exceptions (such as failed preconditions) that may be
        // thrown.
        // TODO(ericgribkoff) Decide if we still want this. Impacts tests like
        //   io.grpc.netty.NettyServerHandlerTest.streamErrorShouldNotCloseChannel()
        deframeFailed(t);
      }
    }

    /**
     * Called to request the given number of messages from the deframer. Must be called
     * from the transport thread.
     */
    public final void requestMessagesFromDeframer(int numMessages) {
      try {
        deframer.request(numMessages);
      } catch (Throwable t) {
        // The deframer will not intentionally throw an exception but instead call deframeFailed
        // directly. This captures other exceptions (such as failed preconditions) that may be
        // thrown.
        // TODO(ericgribkoff) Decide if we still want this try/catch block. Impacts tests like
        //   io.grpc.netty.NettyServerHandlerTest.streamErrorShouldNotCloseChannel()
        deframeFailed(t);
      }
    }

    public final StatsTraceContext getStatsTraceContext() {
      return statsTraceCtx;
    }

    private void setDecompressor(Decompressor decompressor) {
      if (isDeframerScheduledToClose()) {
        return;
      }
      deframer.setDecompressor(decompressor);
    }

    private boolean isReady() {
      synchronized (onReadyLock) {
        return allocated && numSentBytesQueued < DEFAULT_ONREADY_THRESHOLD && !deallocated;
      }
    }

    /**
     * Event handler to be called by the subclass when the stream's headers have passed any
     * connection flow control (i.e., MAX_CONCURRENT_STREAMS). It may call the listener's {@link
     * StreamListener#onReady()} handler if appropriate. This must be called from the transport
     * thread, since the listener may be called back directly.
     */
    protected void onStreamAllocated() {
      checkState(listener() != null);
      synchronized (onReadyLock) {
        checkState(!allocated, "Already allocated");
        allocated = true;
      }
      notifyIfReady();
    }

    /**
     * Notify that the stream does not exist in a usable state any longer. This causes {@link
     * AbstractStream2#isReady()} to return {@code false} from this point forward.
     *
     * <p>This does not generally need to be called explicitly by the transport, as it is handled
     * implicitly by {@link AbstractClientStream2} and {@link AbstractServerStream}.
     */
    protected final void onStreamDeallocated() {
      synchronized (onReadyLock) {
        deallocated = true;
      }
    }

    /**
     * Event handler to be called by the subclass when a number of bytes are being queued for
     * sending to the remote endpoint.
     *
     * @param numBytes the number of bytes being sent.
     */
    private void onSendingBytes(int numBytes) {
      synchronized (onReadyLock) {
        numSentBytesQueued += numBytes;
      }
    }

    /**
     * Event handler to be called by the subclass when a number of bytes has been sent to the remote
     * endpoint. May call back the listener's {@link StreamListener#onReady()} handler if
     * appropriate.  This must be called from the transport thread, since the listener may be called
     * back directly.
     *
     * @param numBytes the number of bytes that were sent.
     */
    public final void onSentBytes(int numBytes) {
      boolean doNotify;
      synchronized (onReadyLock) {
        checkState(allocated,
            "onStreamAllocated was not called, but it seems the stream is active");
        boolean belowThresholdBefore = numSentBytesQueued < DEFAULT_ONREADY_THRESHOLD;
        numSentBytesQueued -= numBytes;
        boolean belowThresholdAfter = numSentBytesQueued < DEFAULT_ONREADY_THRESHOLD;
        doNotify = !belowThresholdBefore && belowThresholdAfter;
      }
      if (doNotify) {
        notifyIfReady();
      }
    }

    private void notifyIfReady() {
      boolean doNotify;
      synchronized (onReadyLock) {
        doNotify = isReady();
      }
      if (doNotify) {
        listener().onReady();
      }
    }
  }
}
