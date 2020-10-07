package io.grpc.census;

import io.grpc.ClientInterceptor;
import io.opencensus.trace.Tracing;

public class Interceptors {
  private Interceptors() {
  }

  public static ClientInterceptor getClientInterceptor() {
    CensusTracingModule censusTracing =
            new CensusTracingModule(
                    Tracing.getTracer(),
                    Tracing.getPropagationComponent().getTraceContextFormat());
    return censusTracing.getClientInterceptor();
  }

}
