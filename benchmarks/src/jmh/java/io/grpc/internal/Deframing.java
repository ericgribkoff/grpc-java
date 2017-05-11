package io.grpc.internal;

import static java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory;

import com.google.common.util.concurrent.UncaughtExceptionHandlers;
import io.grpc.Codec;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.benchmarks.Utils;
import io.grpc.benchmarks.proto.BenchmarkServiceGrpc;
import io.grpc.benchmarks.proto.Messages.PayloadType;
import io.grpc.benchmarks.proto.Messages.SimpleRequest;
import io.grpc.benchmarks.proto.Messages.SimpleResponse;
import io.grpc.internal.MessageDeframer.Sink;
import io.grpc.internal.MessageDeframer.Source;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Created by ericgribkoff on 5/11/17.
 */
@State(Scope.Benchmark)
public class Deframing {

  private int responseSize = 100;
  private SimpleRequest request = SimpleRequest.newBuilder().setResponseType(PayloadType.COMPRESSABLE).setResponseSize(responseSize).build();

  private Executor executor;

  private int maxMessageSize = 128 * 1024 * 1024;

  private final CountDownLatch latch = new CountDownLatch(1);
  private final List<SimpleResponse> responses = new ArrayList<SimpleResponse>();


  private MessageDeframer deframerInTransportThread;

  private MessageDeframer deframerInClientThread;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    deframerInTransportThread = new MessageDeframer(sinkListener, sourceListener,
        Codec.Identity.NONE, maxMessageSize, StatsTraceContext.NOOP, "");
    deframerInClientThread = new MessageDeframer(clientThreadSinkListener, sourceListener,
        Codec.Identity.NONE, maxMessageSize, StatsTraceContext.NOOP, "");
    executor = new SerializingExecutor(new ForkJoinPool(
        Runtime.getRuntime().availableProcessors(),
        new ForkJoinWorkerThreadFactory() {
          final AtomicInteger num = new AtomicInteger();
          @Override
          public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            ForkJoinWorkerThread thread = defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setDaemon(true);
            thread.setName("grpc-client-app-" + "-" + num.getAndIncrement());
            return thread;
          }
        }, UncaughtExceptionHandlers.systemExit(), true /* async */));
  }

  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    deframerInTransportThread.sink().scheduleCloseWhenComplete();
    deframerInClientThread.sink().scheduleCloseWhenComplete();
  }

  private MessageDeframer.Sink.Listener sinkListener = new Sink.Listener() {
    @Override
    public void scheduleDeframerSource(Source source) {
      InputStream message;
      while ((message = source.next()) != null) {
        responses.add(BenchmarkServiceGrpc.METHOD_UNARY_CALL.parseResponse(message));
        try {
          message.close();
        } catch (Exception e) {

        }
        latch.countDown();
      }
    }
  };

  private MessageDeframer.Sink.Listener clientThreadSinkListener = new Sink.Listener() {
    @Override
    public void scheduleDeframerSource(final Source source) {
      class RunInClientThread implements Runnable {
        @Override
        public void run() {
          InputStream message;
          while ((message = source.next()) != null) {
            responses.add(BenchmarkServiceGrpc.METHOD_UNARY_CALL.parseResponse(message));
            try {
              message.close();
            } catch (Exception e) {

            }
            latch.countDown();
          }
        }
      }

      executor.execute(new RunInClientThread());
    }
  };

  private MessageDeframer.Source.Listener sourceListener = new Source.Listener() {
    @Override
    public void bytesRead(int numBytes) {

    }

    @Override
    public void deframerClosed(boolean hasPartialMessage) {

    }

    @Override
    public void deframeFailed(Throwable t) {

    }
  };


  /**
   * Javadoc comment.
   */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public SimpleResponse timeToDeliverSingleMessageInTransportThread() throws Exception {
    byte[] serverResponse = Utils.makeResponse(request).toByteArray();

    // Maybe better if we just make the deframer and avoid client stream altogether. Will need simulated call executor...
    deframerInTransportThread.sink().request(1);
    int responseNumBytes = serverResponse.length;
    deframerInTransportThread.sink().deframe(buffer(
        new byte[] {0, (byte) (responseNumBytes >>> 24), (byte) (responseNumBytes >>> 16),
            (byte) (responseNumBytes >>> 8), (byte) responseNumBytes}));
    deframerInTransportThread.sink().deframe(buffer(serverResponse));

    latch.await(10, TimeUnit.SECONDS);
    return responses.get(0);
  }

  /**
   * Javadoc comment.
   */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public SimpleResponse timeToDeliverSingleMessageInClientThread() throws Exception {
    byte[] serverResponse = Utils.makeResponse(request).toByteArray();

    // Maybe better if we just make the deframer and avoid client stream altogether. Will need simulated call executor...
    deframerInClientThread.sink().request(1);
    int responseNumBytes = serverResponse.length;
    deframerInClientThread.sink().deframe(buffer(
        new byte[] {0, (byte) (responseNumBytes >>> 24), (byte) (responseNumBytes >>> 16),
            (byte) (responseNumBytes >>> 8), (byte) responseNumBytes}));
    deframerInClientThread.sink().deframe(buffer(serverResponse));

    latch.await(10, TimeUnit.SECONDS);
    return responses.get(0);
  }


//  private int numBytesReceivedAtATime = 50;
//  /**
//   * Javadoc comment.
//   */
//  @Benchmark
//  @BenchmarkMode(Mode.SampleTime)
//  @OutputTimeUnit(TimeUnit.NANOSECONDS)
//  public SimpleResponse timeToDeliverSingleMessageInTransportThreadMessageReceivedPiecemeal() throws Exception {
//    byte[] serverResponse = Utils.makeResponse(request).toByteArray();
//
//    // Maybe better if we just make the deframer and avoid client stream altogether. Will need simulated call executor...
//    deframerInTransportThread.sink().request(1);
//    int responseNumBytes = serverResponse.length;
//    deframerInTransportThread.sink().deframe(buffer(
//        new byte[] {0, (byte) (responseNumBytes >>> 24), (byte) (responseNumBytes >>> 16),
//            (byte) (responseNumBytes >>> 8), (byte) responseNumBytes}));
//    int j;
//    for (int i = 0; i < responseNumBytes; i += numBytesReceivedAtATime) {
//      j = Math.min(numBytesReceivedAtATime, responseNumBytes - i);
//      deframerInTransportThread.sink().deframe(ReadableBuffers.wrap(serverResponse, i, j));
//    }
//
//    latch.await(10, TimeUnit.SECONDS);
//    return responses.get(0);
//  }

//  /**
//   * Javadoc comment.
//   */
//  @Benchmark
//  @BenchmarkMode(Mode.SampleTime)
//  @OutputTimeUnit(TimeUnit.NANOSECONDS)
//  public SimpleResponse timeToDeliverSingleMessageInClientThreadMessageReceivedPiecemeal() throws Exception {
//    byte[] serverResponse = Utils.makeResponse(request).toByteArray();
//
//    // Maybe better if we just make the deframer and avoid client stream altogether. Will need simulated call executor...
//    deframerInClientThread.sink().request(1);
//    int responseNumBytes = serverResponse.length;
//    deframerInClientThread.sink().deframe(buffer(
//        new byte[] {0, (byte) (responseNumBytes >>> 24), (byte) (responseNumBytes >>> 16),
//            (byte) (responseNumBytes >>> 8), (byte) responseNumBytes}));
//    int j;
//    for (int i = 0; i < responseNumBytes; i += numBytesReceivedAtATime) {
//      j = Math.min(numBytesReceivedAtATime, responseNumBytes - i);
//      deframerInClientThread.sink().deframe(ReadableBuffers.wrap(serverResponse, i, j));
//    }
//    latch.await(10, TimeUnit.SECONDS);
//    return responses.get(0);
//  }

  private static ReadableBuffer buffer(byte[] bytes) {
    return ReadableBuffers.wrap(bytes);
  }
}
