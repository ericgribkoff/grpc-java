package io.grpc.reflection.testing;

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
 * <pre>
 * The greeting service definition.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.1.0-SNAPSHOT)",
    comments = "Source: reflection_test_1.proto")
public class ReflectableServiceGrpc {

  private ReflectableServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.reflection.testing.ReflectableService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static final io.grpc.MethodDescriptor<io.grpc.reflection.testing.HelloRequest,
      io.grpc.reflection.testing.HelloReply> METHOD_SAY_HELLO =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "grpc.reflection.testing.ReflectableService", "SayHello"),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.reflection.testing.HelloRequest.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.reflection.testing.HelloReply.getDefaultInstance()));

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ReflectableServiceStub newStub(io.grpc.Channel channel) {
    return new ReflectableServiceStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ReflectableServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new ReflectableServiceBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary and streaming output calls on the service
   */
  public static ReflectableServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new ReflectableServiceFutureStub(channel);
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static abstract class ReflectableServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public void sayHello(io.grpc.reflection.testing.HelloRequest request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(METHOD_SAY_HELLO, responseObserver);
    }

    @java.lang.Override public io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            METHOD_SAY_HELLO,
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.reflection.testing.HelloRequest,
                io.grpc.reflection.testing.HelloReply>(
                  this, METHODID_SAY_HELLO)))
          .build();
    }
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static final class ReflectableServiceStub extends io.grpc.stub.AbstractStub<ReflectableServiceStub> {
    private ReflectableServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ReflectableServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReflectableServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ReflectableServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public void sayHello(io.grpc.reflection.testing.HelloRequest request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.HelloReply> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_SAY_HELLO, getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static final class ReflectableServiceBlockingStub extends io.grpc.stub.AbstractStub<ReflectableServiceBlockingStub> {
    private ReflectableServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ReflectableServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReflectableServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ReflectableServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public io.grpc.reflection.testing.HelloReply sayHello(io.grpc.reflection.testing.HelloRequest request) {
      return blockingUnaryCall(
          getChannel(), METHOD_SAY_HELLO, getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static final class ReflectableServiceFutureStub extends io.grpc.stub.AbstractStub<ReflectableServiceFutureStub> {
    private ReflectableServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ReflectableServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReflectableServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ReflectableServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.reflection.testing.HelloReply> sayHello(
        io.grpc.reflection.testing.HelloRequest request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_SAY_HELLO, getCallOptions()), request);
    }
  }

  private static final int METHODID_SAY_HELLO = 0;

  private static class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final ReflectableServiceImplBase serviceImpl;
    private final int methodId;

    public MethodHandlers(ReflectableServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SAY_HELLO:
          serviceImpl.sayHello((io.grpc.reflection.testing.HelloRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.reflection.testing.HelloReply>) responseObserver);
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

  private static class ReflectableServiceServiceDescriptor extends io.grpc.protobuf.ProtobufServiceDescriptor {
    public ReflectableServiceServiceDescriptor(String name, io.grpc.MethodDescriptor<?, ?>... methods) {
      super(name, methods);
    }

    public ReflectableServiceServiceDescriptor(String name, java.util.Collection<io.grpc.MethodDescriptor<?, ?>> methods) {
      super(name, methods);
    }

    @java.lang.Override
    public ReflectableServiceServiceDescriptor withMethods(java.util.Collection<io.grpc.MethodDescriptor<?, ?>> methods) {
      return new ReflectableServiceServiceDescriptor(getName(), methods);
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFile() {
      return io.grpc.reflection.testing.ReflectionTest1Proto.getDescriptor();
    }
  }

  public static ReflectableServiceServiceDescriptor getServiceDescriptor() {
    return new ReflectableServiceServiceDescriptor(SERVICE_NAME,
        METHOD_SAY_HELLO);
  }

}
