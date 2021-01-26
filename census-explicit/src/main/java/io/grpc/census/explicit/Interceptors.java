/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.census.explicit;

import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.ServerInterceptor2;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerStreamTracer;
import java.util.Arrays;

/**
 * Supplies factory methods for {@link ClientInterceptor} and {@link ServerInterceptor2}
 * implementations that add OpenCensus instrumention.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/????")
public class Interceptors {
  private Interceptors() {
  }

  /** Returns {@link ClientInterceptor} that adds stats instrumentation. */
  public static ClientInterceptor getStatsClientInterceptor() {
    return InternalCensusStatsAccessor.getClientInterceptor(true, true, true);
  }

  /** Returns {@link ClientInterceptor} that adds tracing. */
  public static ClientInterceptor getTracingClientInterceptor() {
    return InternalCensusTracingAccessor.getClientInterceptor();
  }

  private static class ServerTracerFactoryInterceptor implements ServerInterceptor2 {
    private final ServerStreamTracer.Factory factory;

    private ServerTracerFactoryInterceptor(ServerStreamTracer.Factory factory) {
      this.factory = factory;
    }

    @Override
    public <ReqT, RespT> ServerMethodDefinition<ReqT, RespT> interceptMethodDefinition(
        ServerMethodDefinition<ReqT, RespT> method) {
      return method.withStreamTracerFactories(Arrays.asList(factory));
    }
  }

  /** Returns {@link ServerInterceptor2} that adds stats instrumentation. */
  public static ServerInterceptor2 getStatsServerInterceptor() {
    return new ServerTracerFactoryInterceptor(
        InternalCensusStatsAccessor.getServerStreamTracerFactory(true, true, true));
  }

  /** Returns {@link ServerInterceptor2} that adds tracing. */
  public static ServerInterceptor2 getTracingServerInterceptor() {
    return new ServerTracerFactoryInterceptor(
        InternalCensusTracingAccessor.getServerStreamTracerFactory());
  }
}
