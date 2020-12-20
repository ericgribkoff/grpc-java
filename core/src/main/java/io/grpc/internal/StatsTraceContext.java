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
import javax.annotation.concurrent.ThreadSafe;

/** The stats and tracing information for a stream. */
@ThreadSafe // Hrm :/
public class StatsTraceContext {
  // TODO: rename
  public interface ServerIsReadyListener {
    public void serverIsReady();
  }

  public static final StatsTraceContext NOOP = new StatsTraceContext(new StreamTracer[0]);

  private final StreamTracer[] tracers;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  // TODO: clarify semantics, make private. Rename to "allTracersReady" or something
  private volatile boolean useInterceptorTracers;
  private StreamTracer[] interceptorTracers;
  private final boolean isServer;

  /** Factory method for the client-side. */
  public static StatsTraceContext newClientContext(
      final CallOptions callOptions, final Attributes transportAttrs, Metadata headers) {
    List<ClientStreamTracer.Factory> factories = callOptions.getStreamTracerFactories();
    if (factories.isEmpty()) {
      return NOOP;
    }
    ClientStreamTracer.StreamInfo info =
        ClientStreamTracer.StreamInfo.newBuilder()
            .setTransportAttrs(transportAttrs)
            .setCallOptions(callOptions)
            .build();
    // This array will be iterated multiple times per RPC. Use primitive array instead of Collection
    // so that for-each doesn't create an Iterator every time.
    StreamTracer[] tracers = new StreamTracer[factories.size()];
    for (int i = 0; i < tracers.length; i++) {
      tracers[i] = factories.get(i).newClientStreamTracer(info, headers);
    }
    return new StatsTraceContext(tracers);
  }

  /** Factory method for the server-side. */
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
    return new StatsTraceContext(tracers, true);
  }

  // TODO: volatile?
  private ServerIsReadyListener serverIsReadyListener;
  private volatile boolean interceptorTracersSet;

  /** TODO. */
  public Context.CancellableContext setInterceptorStreamTracersAndFilterContext(
      List<ServerStreamTracer> newTracers, Context.CancellableContext context) {
    checkState(!interceptorTracersSet, "Interceptor tracers already set");
    if (!newTracers.isEmpty()) {
      interceptorTracers = new StreamTracer[newTracers.size()];
      for (int i = 0; i < interceptorTracers.length; i++) {
        ServerStreamTracer tracer = newTracers.get(i);
        context = tracer.filterContext(context).withCancellation();
        interceptorTracers[i] = tracer;
      }
      useInterceptorTracers = true;
    }
    interceptorTracersSet = true;
    if (serverIsReadyListener != null) {
      serverIsReadyListener.serverIsReady();
    }
    return context;
  }

  @VisibleForTesting
  StatsTraceContext(StreamTracer[] tracers) {
    this(tracers, false);
  }

  @VisibleForTesting
  StatsTraceContext(StreamTracer[] tracers, boolean isServer) {
    this.tracers = tracers;
    this.isServer = isServer;
  }

  /** Returns a copy of the tracer list. */
  @VisibleForTesting
  public List<StreamTracer> getTracersForTest() {
    return new ArrayList<>(Arrays.asList(tracers));
  }

  /**
   * See {@link ClientStreamTracer#outboundHeaders}. For client-side only.
   *
   * <p>Transport-specific, thus should be called by transport implementations.
   */
  public void clientOutboundHeaders() {
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).outboundHeaders();
    }
  }

  /**
   * See {@link ClientStreamTracer#inboundHeaders}. For client-side only.
   *
   * <p>Called from abstract stream implementations.
   */
  public void clientInboundHeaders() {
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).inboundHeaders();
    }
  }

  /**
   * See {@link ClientStreamTracer#inboundTrailers}. For client-side only.
   *
   * <p>Called from abstract stream implementations.
   */
  public void clientInboundTrailers(Metadata trailers) {
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).inboundTrailers(trailers);
    }
  }

  /**
   * See {@link ServerStreamTracer#filterContext}. For server-side only.
   *
   * <p>Called from {@link io.grpc.internal.ServerImpl}.
   */
  public <ReqT, RespT> Context serverFilterContext(Context context) {
    Context ctx = checkNotNull(context, "context");
    checkState(!useInterceptorTracers, "Already ready to use???"); // TODO: remove
    for (StreamTracer tracer : tracers) {
      ctx = ((ServerStreamTracer) tracer).filterContext(ctx);
      checkNotNull(ctx, "%s returns null context", tracer);
    }
    return ctx;
  }

  /**
   * See {@link ServerStreamTracer#serverCallStarted}. For server-side only. This method must be
   * invoked before any other calls to StatsTraceContext, with the exception of streamClosed
   * (inherently racy, since transport can close stream while call is getting created) and
   * (possibly) serverFilterContext
   *
   * <p>Called from {@link io.grpc.internal.ServerImpl}.
   */
  // Invoking this serves as signal that "interceptor stream tracers" are ready
  // For non-InProcess transports, only streamClosed (and serverFilterContext) can be
  // invoked before this happens (all messages etc will only come *after* call has started)
  // For InProcess, we want to buffer the inboundMessage and inboundMessageRead calls for
  // "correctness"
  // Would not need 'useInterceptorTracers' check here or in any of the other methods *except*
  // streamClosed if we add that the contract here is that this must be called before any
  // server trace methods are invoked.
  // Client can just initialize interceptorTracers = []
  public void serverCallStarted(final ServerCallInfo<?, ?> callInfo) {
    for (StreamTracer tracer : tracers) {
      ((ServerStreamTracer) tracer).serverCallStarted(callInfo);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        ((ServerStreamTracer) tracer).serverCallStarted(callInfo);
      }
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
      for (StreamTracer tracer : tracers) {
        tracer.streamClosed(status);
      }
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.streamClosed(status);
      }
    }
  }

  /**
   * See {@link StreamTracer#outboundMessage(int)}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundMessage(int seqNo) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundMessage(seqNo);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.outboundMessage(seqNo);
      }
    }
  }

  /**
   * See {@link StreamTracer#inboundMessage(int)}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundMessage(int seqNo) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundMessage(seqNo);
    }
    if (!interceptorTracersSet && isServer) {
      throw new RuntimeException("not ready!");
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.inboundMessage(seqNo);
      }
    }
  }

  /**
   * See {@link StreamTracer#outboundMessageSent}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundMessageSent(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
      }
    }
  }

  /**
   * See {@link StreamTracer#inboundMessageRead}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundMessageRead(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);
      }
    }
  }

  /**
   * See {@link StreamTracer#outboundUncompressedSize}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundUncompressedSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundUncompressedSize(bytes);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.outboundUncompressedSize(bytes);
      }
    }
  }

  /**
   * See {@link StreamTracer#outboundWireSize}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundWireSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundWireSize(bytes);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.outboundWireSize(bytes);
      }
    }
  }

  /**
   * See {@link StreamTracer#inboundUncompressedSize}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundUncompressedSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundUncompressedSize(bytes);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.inboundUncompressedSize(bytes);
      }
    }
  }

  /**
   * See {@link StreamTracer#inboundWireSize}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundWireSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundWireSize(bytes);
    }
    if (useInterceptorTracers) {
      for (StreamTracer tracer : interceptorTracers) {
        tracer.inboundWireSize(bytes);
      }
    }
  }

  public void setServerIsReadyListener(ServerIsReadyListener listener) {
    this.serverIsReadyListener = listener;
  }
}
