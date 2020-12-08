/*
 * Copyright 2016 The gRPC Authors
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
import com.google.common.math.Stats;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerStreamTracer.ServerCallInfo;
import io.grpc.Status;
import io.grpc.StreamTracer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The stats and tracing information for a stream.
 */
@ThreadSafe
public final class StatsTraceContext {
  public static final StatsTraceContext NOOP = new StatsTraceContext(new StreamTracer[0]);

  private StreamTracer[] tracers;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  public volatile boolean readyToUse; // TODO: clarify semantics, make private. Rename to "allTracersReady" or something

  @GuardedBy("this")
  private List<Runnable> pendingEvents = new ArrayList<>();

//  // These allocations might be problematic, since they may never be used. But just doubles the number of allocations...
//  private StreamTracer[] interceptorTracers;
//  private final AtomicBoolean interceptorTracersSet = new AtomicBoolean(false);

  /**
   * Factory method for the client-side.
   */
  public static StatsTraceContext newClientContext(
      final CallOptions callOptions, final Attributes transportAttrs, Metadata headers) {
    List<ClientStreamTracer.Factory> factories = callOptions.getStreamTracerFactories();
    if (factories.isEmpty()) {
      return NOOP;
    }
    ClientStreamTracer.StreamInfo info =
        ClientStreamTracer.StreamInfo.newBuilder()
            .setTransportAttrs(transportAttrs).setCallOptions(callOptions).build();
    // This array will be iterated multiple times per RPC. Use primitive array instead of Collection
    // so that for-each doesn't create an Iterator every time.
    StreamTracer[] tracers = new StreamTracer[factories.size()];
    for (int i = 0; i < tracers.length; i++) {
      tracers[i] = factories.get(i).newClientStreamTracer(info, headers);
    }
    return new StatsTraceContext(tracers);
  }

  /**
   * Factory method for the server-side.
   */
  // TODO: delayed
  public static StatsTraceContext newServerContext(
      List<? extends ServerStreamTracer.Factory> factories,
      String fullMethodName,
      Metadata headers) {
//    if (factories.isEmpty()) {
//      return NOOP;
//    }
    StreamTracer[] tracers = new StreamTracer[factories.size()];
    for (int i = 0; i < tracers.length; i++) {
      tracers[i] = factories.get(i).newServerStreamTracer(fullMethodName, headers);
    }
    return new StatsTraceContext(tracers, false);
  }

  void addStreamTracers(List<? extends ServerStreamTracer> newTracers) {
    if (readyToUse) {
      throw new RuntimeException("Already ready to use, cannot add factories");
    }
    if (!newTracers.isEmpty()) {
      StreamTracer[] replacementTracers = new StreamTracer[tracers.length + newTracers.size()];
      for (int i = 0; i < tracers.length; i++) {
        replacementTracers[i] = tracers[i];
      }
      for (int i = tracers.length; i < replacementTracers.length; i++) {
        replacementTracers[i] = newTracers.get(i - tracers.length);
      }
      this.tracers = replacementTracers;
    }
    synchronized (this) {
      // TODO: make faster, see DelayedStream
      for (Runnable runnable : pendingEvents) {
        runnable.run();
      }
      pendingEvents.clear();
    }
    readyToUse = true;
  }

  StatsTraceContext(StreamTracer[] tracers, boolean readyToUse) {
    this.tracers = tracers;
    this.readyToUse = readyToUse;
  }

  @VisibleForTesting
  StatsTraceContext(StreamTracer[] tracers) {
    this(tracers, true);
  }

//  private StreamTracer[] getStreamTracers() {
//    if (!readyToUse) {
//      throw new RuntimeException("Not ready to use!");
//    }
//    return tracers;
//  }

  /**
   * Returns a copy of the tracer list.
   */
  @VisibleForTesting
  public List<StreamTracer> getTracersForTest() {
    return new ArrayList<>(Arrays.asList(tracers));
  }

  /**
   * See {@link ClientStreamTracer#outboundHeaders}.  For client-side only.
   *
   * <p>Transport-specific, thus should be called by transport implementations.
   */
  public void clientOutboundHeaders() {
    checkState(readyToUse, "Client should always be ready to use");
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).outboundHeaders();
    }
  }

  /**
   * See {@link ClientStreamTracer#inboundHeaders}.  For client-side only.
   *
   * <p>Called from abstract stream implementations.
   */
  public void clientInboundHeaders() {
    checkState(readyToUse, "Client should always be ready to use");
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).inboundHeaders();
    }
  }

  /**
   * See {@link ClientStreamTracer#inboundTrailers}.  For client-side only.
   *
   * <p>Called from abstract stream implementations.
   */
  public void clientInboundTrailers(Metadata trailers) {
    checkState(readyToUse, "Client should always be ready to use");
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).inboundTrailers(trailers);
    }
  }

  /**
   * See {@link ServerStreamTracer#filterContext}.  For server-side only.
   *
   * <p>Called from {@link io.grpc.internal.ServerImpl}.
   */
  public <ReqT, RespT> Context serverFilterContext(Context context) {
    Context ctx = checkNotNull(context, "context");
    for (StreamTracer tracer : tracers) { // TODO: Skipping check of readyToUse, ok?
      ctx = ((ServerStreamTracer) tracer).filterContext(ctx);
      checkNotNull(ctx, "%s returns null context", tracer);
    }
    return ctx;
  }

  /**
   * See {@link ServerStreamTracer#serverCallStarted}.  For server-side only.
   *
   * <p>Called from {@link io.grpc.internal.ServerImpl}.
   */
  public void serverCallStarted(final ServerCallInfo<?, ?> callInfo) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        ((ServerStreamTracer) tracer).serverCallStarted(callInfo);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            ((ServerStreamTracer) tracer).serverCallStarted(callInfo);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#streamClosed}. This may be called multiple times, and only the first
   * value will be taken.
   *
   * <p>Called from abstract stream implementations.
   */
  public void streamClosed(Status status) {
    if (closed.compareAndSet(false, true)) {
      for (StreamTracer tracer : tracers) { // TODO: Skipping check of readyToUse, ok?
        tracer.streamClosed(status);
      }
    }
  }

  /**
   * See {@link StreamTracer#outboundMessage(int)}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundMessage(final int seqNo) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.outboundMessage(seqNo);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.outboundMessage(seqNo);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#inboundMessage(int)}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundMessage(final int seqNo) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.inboundMessage(seqNo);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.inboundMessage(seqNo);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#outboundMessageSent}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundMessageSent(final int seqNo, final long optionalWireSize, final long optionalUncompressedSize) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#inboundMessageRead}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundMessageRead(final int seqNo, final long optionalWireSize, final long optionalUncompressedSize) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#outboundUncompressedSize}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundUncompressedSize(final long bytes) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.outboundUncompressedSize(bytes);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.outboundUncompressedSize(bytes);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#outboundWireSize}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundWireSize(final long bytes) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.outboundWireSize(bytes);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.outboundWireSize(bytes);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#inboundUncompressedSize}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundUncompressedSize(final long bytes) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.inboundUncompressedSize(bytes);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.inboundUncompressedSize(bytes);
          }
        }
      });
    }
  }

  /**
   * See {@link StreamTracer#inboundWireSize}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundWireSize(final long bytes) {
    if (readyToUse) {
      for (StreamTracer tracer : tracers) {
        tracer.inboundWireSize(bytes);
      }
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          for (StreamTracer tracer : tracers) {
            tracer.inboundWireSize(bytes);
          }
        }
      });
    }
  }

  private void delayOrExecute(Runnable runnable) {
    synchronized (this) {
      if (!readyToUse) {
        pendingEvents.add(runnable);
        return;
      }
    }
    runnable.run();
  }
}
