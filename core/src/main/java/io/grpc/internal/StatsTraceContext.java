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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StreamTracer;
import java.util.List;

/** The stats and tracing information for a stream. */
public interface StatsTraceContext {
  Context.CancellableContext setInterceptorStreamTracersAndFilterContext(
      List<ServerStreamTracer> newTracers, Context.CancellableContext context);

  @VisibleForTesting
  List<StreamTracer> getTracersForTest();

  void clientOutboundHeaders();

  void clientInboundHeaders();

  void clientInboundTrailers(Metadata trailers);

  <ReqT, RespT> Context serverFilterContext(Context context);

  // Invoking this serves as signal that "interceptor stream tracers" are ready
  // For non-InProcess transports, only streamClosed (and serverFilterContext) can be
  // invoked before this happens (all messages etc will only come *after* call has started)
  // For InProcess, we want to buffer the inboundMessage and inboundMessageRead calls for
  // "correctness"
  // Would not need 'useInterceptorTracers' check here or in any of the other methods *except*
  // streamClosed if we add that the contract here is that this must be called before any
  // server trace methods are invoked.
  // Client can just initialize interceptorTracers = []
  void serverCallStarted(ServerStreamTracer.ServerCallInfo<?, ?> callInfo);

  void streamClosed(Status status);

  void outboundMessage(int seqNo);

  void inboundMessage(int seqNo);

  void outboundMessageSent(int seqNo, long optionalWireSize, long optionalUncompressedSize);

  void inboundMessageRead(int seqNo, long optionalWireSize, long optionalUncompressedSize);

  void outboundUncompressedSize(long bytes);

  void outboundWireSize(long bytes);

  void inboundUncompressedSize(long bytes);

  void inboundWireSize(long bytes);

  public interface ServerIsReadyListener {
    void serverIsReady();
  }
}
