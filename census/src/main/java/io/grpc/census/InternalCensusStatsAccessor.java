/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.census;

import io.grpc.ClientInterceptor;
import io.grpc.Internal;
import io.grpc.ServerStreamTracer;

/**
 * Accessor for getting {@link ClientInterceptor} or {@link ServerStreamTracer.Factory} with
 * default Census stats implementation.
 */
@Internal
public final class InternalCensusStatsAccessor {
  // Prevent instantiation.
  private InternalCensusStatsAccessor() {
  }

  /**
   * Returns a {@link ClientInterceptor} with default stats implementation.
   */
  public static ClientInterceptor getClientInterceptor(
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    return io.grpc.census.explicit.InternalCensusStatsAccessor.getClientInterceptor(
        recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics);
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with default stats implementation.
   */
  public static ServerStreamTracer.Factory getServerStreamTracerFactory(
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    return io.grpc.census.explicit.InternalCensusStatsAccessor.getServerStreamTracerFactory(
        recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics);
  }
}
