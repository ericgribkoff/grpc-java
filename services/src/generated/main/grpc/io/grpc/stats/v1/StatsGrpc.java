package io.grpc.stats.v1;

import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.1.0-SNAPSHOT)",
    comments = "Source: stats.proto")
public class StatsGrpc {

  private StatsGrpc() {}

  public static final String SERVICE_NAME = "grpc.stats.v1.Stats";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static final io.grpc.MethodDescriptor<io.grpc.stats.v1.StatsRequest,
      io.grpc.stats.v1.StatsResponse> METHOD_GET_STATS =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "grpc.stats.v1.Stats", "GetStats"),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.stats.v1.StatsRequest.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.stats.v1.StatsResponse.getDefaultInstance()));

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static StatsStub newStub(io.grpc.Channel channel) {
    return new StatsStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static StatsBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new StatsBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary and streaming output calls on the service
   */
  public static StatsFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new StatsFutureStub(channel);
  }

  /**
   */
  public static abstract class StatsImplBase implements io.grpc.BindableService {

    /**
     */
    public void getStats(io.grpc.stats.v1.StatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.stats.v1.StatsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(METHOD_GET_STATS, responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            METHOD_GET_STATS,
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.stats.v1.StatsRequest,
                io.grpc.stats.v1.StatsResponse>(
                  this, METHODID_GET_STATS)))
          .build();
    }
  }

  /**
   */
  public static final class StatsStub extends io.grpc.stub.AbstractStub<StatsStub> {
    private StatsStub(io.grpc.Channel channel) {
      super(channel);
    }

    private StatsStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected StatsStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new StatsStub(channel, callOptions);
    }

    /**
     */
    public void getStats(io.grpc.stats.v1.StatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.stats.v1.StatsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_GET_STATS, getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class StatsBlockingStub extends io.grpc.stub.AbstractStub<StatsBlockingStub> {
    private StatsBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private StatsBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected StatsBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new StatsBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stats.v1.StatsResponse getStats(io.grpc.stats.v1.StatsRequest request) {
      return blockingUnaryCall(
          getChannel(), METHOD_GET_STATS, getCallOptions(), request);
    }
  }

  /**
   */
  public static final class StatsFutureStub extends io.grpc.stub.AbstractStub<StatsFutureStub> {
    private StatsFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private StatsFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected StatsFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new StatsFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.stats.v1.StatsResponse> getStats(
        io.grpc.stats.v1.StatsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_GET_STATS, getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_STATS = 0;

  private static class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final StatsImplBase serviceImpl;
    private final int methodId;

    public MethodHandlers(StatsImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_STATS:
          serviceImpl.getStats((io.grpc.stats.v1.StatsRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.stats.v1.StatsResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static final class StatsDescriptorSupplier implements io.grpc.protobuf.ProtoFileDescriptorSupplier {
    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.stats.v1.StatsProto.getDescriptor();
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (StatsGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = new io.grpc.ServiceDescriptor(
              SERVICE_NAME,
              new StatsDescriptorSupplier(),
              METHOD_GET_STATS);
        }
      }
    }
    return result;
  }
}
