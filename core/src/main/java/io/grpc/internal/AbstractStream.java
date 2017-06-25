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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.Decompressor;
import io.grpc.internal.StreamListener.MessageProducer;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Queue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * The stream and stream state as used by the application. Must only be called from the sending
 * application thread.
 */
public abstract class AbstractStream implements Stream {
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
   * Stream state as used by the transport. This should only called from the transport thread
   * (except for private interactions with {@code AbstractStream2}).
   */
  public abstract static class TransportState implements MessageDeframer.Listener {
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

    public boolean client = false;

    protected TransportState(int maxMessageSize, StatsTraceContext statsTraceCtx) {
      this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
      deframer = new MessageDeframer(
          this, Codec.Identity.NONE, maxMessageSize, statsTraceCtx, getClass().getName());
    }

    final void setMaxInboundMessageSize(int maxSize) {
      deframer.setMaxInboundMessageSize(maxSize);
    }

    /**
     * Override this method to provide a stream listener.
     */
    protected abstract StreamListener listener();

    private boolean deframeInApplicationThread = true;

    @Override
    public void messagesAvailable(MessageProducer producer) {
//      if (!deframeInApplicationThread) {
//        // This doesn't work. We "unset" the interceptor for earlier calls, which loses the
//        // interceptor for later calls...we need to skip the interceptor altogether when
//        // deframing in application thread.
//        if (producer instanceof InitializingMessageProducer) {
//          ((InitializingMessageProducer) producer).initialize();
//        }
//      }
      listener().messagesAvailable(producer);
    }

    /**
     * Called when a {@link #deframe(ReadableBuffer, boolean)} operation failed.
     *
     * @param cause the actual failure
     */
    protected abstract void deframeFailed(Throwable cause);

    /**
     * Closes this deframer and frees any resources. After this method is called, additional calls
     * will have no effect.
     */
    protected final void closeDeframer(boolean stopDelivery) {
      System.out.println("closeDeframer " + client + " (stopDelivery=" + stopDelivery + ")");
      if (stopDelivery) {
        deframer.stopDeliveryAndClose();
        // Schedule a call to close in case no current operations pick up the stopDelivery flag.
        messagesAvailable(new InitializingMessageProducer(deframer,
          new Runnable() {
            @Override
            public void run() {
              deframer.close();
            }
          }, deframeInApplicationThread, "stopDeliveryAndClose " + client));
      } else {
        messagesAvailable(new InitializingMessageProducer(deframer,
          new Runnable() {
            @Override
            public void run() {
              deframer.closeWhenComplete();
            }
          }, deframeInApplicationThread, "closeWhenComplete " + client));
      }
    }

    /**
     * Called to parse a received frame and attempt delivery of any completed
     * messages. Must be called from the transport thread.
     */
    protected final void deframe(final ReadableBuffer frame, final boolean endOfStream) {
      System.out.println("deframe " + client);
      messagesAvailable(new InitializingMessageProducer(deframer,
        new Runnable() {
          @Override
          public void run() {
            if (deframer.isClosed()) {
              frame.close();
              return;
            }
            try {
              deframer.deframe(frame, endOfStream);
            } catch (Throwable t) {
              deframeFailed(t);
              deframer.close(); // unrecoverable state
            }
          }
        }, deframeInApplicationThread, "deframe " + client));
    }

    /**
     * Called to request the given number of messages from the deframer. Must be called
     * from the transport thread.
     */
    public final void requestMessagesFromDeframer(final int numMessages) {
      System.out.println("requestMessagesFromDeframer " + client);
      messagesAvailable(new InitializingMessageProducer(deframer,
        new Runnable() {
          @Override
          public void run() {
            if (deframer.isClosed()) {
              return;
            }
            try {
              deframer.request(numMessages);
            } catch (Throwable t) {
              deframeFailed(t);
              deframer.close(); // unrecoverable state
            }
          }
        }, deframeInApplicationThread, "request " + client));
    }

    public final StatsTraceContext getStatsTraceContext() {
      return statsTraceCtx;
    }

    private void setDecompressor(Decompressor decompressor) {
      if (deframer.isClosed()) {
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
     * AbstractStream#isReady()} to return {@code false} from this point forward.
     *
     * <p>This does not generally need to be called explicitly by the transport, as it is handled
     * implicitly by {@link AbstractClientStream} and {@link AbstractServerStream}.
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

    // TODO(ericgribkoff) Separate this into Initializing and Intercepting
    // (close calls don't need the intercepting part)
    // TODO(ericgribkoff) See if this should be non-static
    private static class InitializingMessageProducer implements StreamListener.MessageProducer {
      private final Runnable runnable;
      private final MessageDeframer deframer;
      private final Queue<InputStream> messageQueue = new LinkedList<InputStream>();
      private final String tag;
      private final boolean deframeInApplicationThread;
      private boolean initialized = false;

      private InitializingMessageProducer(MessageDeframer deframer, Runnable runnable,
          boolean deframeInApplicationThread, String tag) {
        this.deframer = deframer;
        this.runnable = runnable;
        this.deframeInApplicationThread = deframeInApplicationThread;
        this.tag = tag;

        if (!deframeInApplicationThread) {
          initialize();
        }
      }

      private void initialize() {
        if (!initialized) {
          if (deframeInApplicationThread) {
            deframer.messageInterceptor = new MessageDeframer.MessageInterceptor() {
              @Override
              public void messageRead(InputStream inputStream) {
                System.out.println("somebody's messageRead called " + tag);
                messageQueue.add(inputStream);
              }
            };
          }
          System.out.println("initialized deframer's messageInterceptor " + tag);
          runnable.run();
          initialized = true;
        }
      }

      @Nullable
      @Override
      public InputStream next() {
        initialize();
        InputStream message = messageQueue.poll();
        if (deframeInApplicationThread && message == null) {
          deframer.messageInterceptor = null;
          System.out.println("unsetting deframer.messageInterceptor " + tag);
        }
        return message;
      }
    }
  }
}
