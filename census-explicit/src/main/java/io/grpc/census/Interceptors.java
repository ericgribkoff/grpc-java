package io.grpc.census;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.ClientInterceptor;
import io.opencensus.trace.Tracing;

public class Interceptors {
  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  private Interceptors() {
  }

  public static ClientInterceptor getTracingClientInterceptor() {
    CensusTracingModule censusTracing =
            new CensusTracingModule(
                    Tracing.getTracer(),
                    Tracing.getPropagationComponent().getTraceContextFormat());
    return censusTracing.getClientInterceptor();
  }


  public static ClientInterceptor getStatsClientInterceptor() {
    CensusStatsModule censusStats =
            new CensusStatsModule(
                    STOPWATCH_SUPPLIER,
                    true, /* propagateTags */
                    true,
                    true,
                    true);
    return censusStats.getClientInterceptor();
  }

}
