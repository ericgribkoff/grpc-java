package io.grpc.examples.helloworld;

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
    comments = "Source: helloworld.proto")
public class myServiceGrpc {

  private myServiceGrpc() {}

  public static final String SERVICE_NAME = "helloworld.myService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static final io.grpc.MethodDescriptor<io.grpc.examples.helloworld.Empty,
      io.grpc.examples.helloworld.Empty> METHOD_MY_METHOD =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "helloworld.myService", "myMethod"),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.examples.helloworld.Empty.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.examples.helloworld.Empty.getDefaultInstance()));

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static myServiceStub newStub(io.grpc.Channel channel) {
    return new myServiceStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static myServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new myServiceBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary and streaming output calls on the service
   */
  public static myServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new myServiceFutureStub(channel);
  }

  /**
   */
  public static abstract class myServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public void myMethod(io.grpc.examples.helloworld.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.Empty> responseObserver) {
      asyncUnimplementedUnaryCall(METHOD_MY_METHOD, responseObserver);
    }

    @java.lang.Override public io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            METHOD_MY_METHOD,
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.examples.helloworld.Empty,
                io.grpc.examples.helloworld.Empty>(
                  this, METHODID_MY_METHOD)))
          .build();
    }
  }

  /**
   */
  public static final class myServiceStub extends io.grpc.stub.AbstractStub<myServiceStub> {
    private myServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private myServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected myServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new myServiceStub(channel, callOptions);
    }

    /**
     */
    public void myMethod(io.grpc.examples.helloworld.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.Empty> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_MY_METHOD, getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class myServiceBlockingStub extends io.grpc.stub.AbstractStub<myServiceBlockingStub> {
    private myServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private myServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected myServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new myServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.examples.helloworld.Empty myMethod(io.grpc.examples.helloworld.Empty request) {
      return blockingUnaryCall(
          getChannel(), METHOD_MY_METHOD, getCallOptions(), request);
    }
  }

  /**
   */
  public static final class myServiceFutureStub extends io.grpc.stub.AbstractStub<myServiceFutureStub> {
    private myServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private myServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected myServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new myServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.examples.helloworld.Empty> myMethod(
        io.grpc.examples.helloworld.Empty request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_MY_METHOD, getCallOptions()), request);
    }
  }

  private static final int METHODID_MY_METHOD = 0;

  private static class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final myServiceImplBase serviceImpl;
    private final int methodId;

    public MethodHandlers(myServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_MY_METHOD:
          serviceImpl.myMethod((io.grpc.examples.helloworld.Empty) request,
              (io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.Empty>) responseObserver);
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

  private static class myServiceServiceDescriptor extends io.grpc.protobuf.ProtobufServiceDescriptor {
    public myServiceServiceDescriptor(String name, io.grpc.MethodDescriptor<?, ?>... methods) {
      super(name, methods);
    }

    public myServiceServiceDescriptor(String name, java.util.Collection<io.grpc.MethodDescriptor<?, ?>> methods) {
      super(name, methods);
    }

    @java.lang.Override
    public myServiceServiceDescriptor withMethods(java.util.Collection<io.grpc.MethodDescriptor<?, ?>> methods) {
      return new myServiceServiceDescriptor(getName(), methods);
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFile() {
      return io.grpc.examples.helloworld.HelloWorldProto.getDescriptor();
    }
  }

  public static myServiceServiceDescriptor getServiceDescriptor() {
    return new myServiceServiceDescriptor(SERVICE_NAME,
        METHOD_MY_METHOD);
  }

}
