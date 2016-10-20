/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.examples.helloworld;

import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;
import io.grpc.HandlerRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.reflection.ProtoServiceDescriptor;
import io.grpc.protobuf.reflection.ProtoReflectableServerBuilder;
import io.grpc.reflection.v1alpha.ErrorResponse;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ListServiceResponse;
//import io.grpc.reflection.v1alpha.MessageRequestCase;
import io.grpc.reflection.v1alpha.ServerReflectionProto;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.util.logging.Logger;
import javax.annotation.Nullable;

import java.lang.reflect.*;


/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());

  /* The port on which the server should run */
  private int port = 50052;
  private Server server;

  private void start() throws IOException {

    // Add empty unary service
//    String serviceName = "grpc.testing.myService";
//    String methodName = "myMethod";
//    MethodDescriptor<com.google.protobuf.EmptyProtos.Empty, com.google.protobuf.EmptyProtos.Empty> methodDescriptor = 
//        MethodDescriptor.create(
//        MethodDescriptor.MethodType.UNKNOWN,
//        MethodDescriptor.generateFullMethodName(serviceName, methodName),
//        io.grpc.protobuf.ProtoUtils.marshaller(com.google.protobuf.EmptyProtos.Empty.getDefaultInstance()),
//        io.grpc.protobuf.ProtoUtils.marshaller(com.google.protobuf.EmptyProtos.Empty.getDefaultInstance()));
////        false, false,
////        com.google.protobuf.EmptyProtos.getDescriptor());
//    ServerServiceDefinition.Builder serviceBuilder = ServerServiceDefinition.builder(
//        new ProtobufServiceDescriptor(serviceName, methodDescriptor));
//    serviceBuilder.addMethod(methodDescriptor,
//        asyncUnaryCall(new MethodHandlers<com.google.protobuf.EmptyProtos.Empty,
//          com.google.protobuf.EmptyProtos.Empty>()));

//    String reflectionServiceName = "grpc.reflection.v1alpha.ServerReflection";
//    String reflectionMethodName = "ServerReflectionInfo";
//    MethodDescriptor<ServerReflectionRequest, ServerReflectionResponse> reflectionMethodDescriptor = 
//        //new ProtobufMethodDescriptor(
//        MethodDescriptor.create(
//          MethodDescriptor.MethodType.BIDI_STREAMING,
//          MethodDescriptor.generateFullMethodName(reflectionServiceName,
//            reflectionMethodName),
//          io.grpc.protobuf.ProtoUtils.marshaller(ServerReflectionRequest.getDefaultInstance()),
//          io.grpc.protobuf.ProtoUtils.marshaller(ServerReflectionResponse.getDefaultInstance()));
//        //  false, false,
//        //  ServerReflectionResponse.getDefaultInstance().getDescriptorForType().getFile());
//        //ServerReflectionProto.getDescriptor());
//    ProtobufServerServiceDefinition.Builder reflectionServiceBuilder = ProtobufServerServiceDefinition.builder(
//        new ServiceDescriptor(reflectionServiceName, reflectionMethodDescriptor), ServerReflectionResponse.getDefaultInstance());
//
//   ReflectionMethodHandlers<ServerReflectionRequest, ServerReflectionResponse> reflectionCall =
//       new ReflectionMethodHandlers<ServerReflectionRequest,
//          ServerReflectionResponse>(this);
//
//    reflectionServiceBuilder.addMethod(reflectionMethodDescriptor, asyncBidiStreamingCall(reflectionCall));
//        //asyncBidiStreamingCall(new ReflectionMethodHandlers<ServerReflectionRequest,
//        //  ServerReflectionResponse>(this)));

    
    // This doesn't work yet - need plumbing to get protobuf service descriptors to ReflectionService
    // instance.
//    server = ServerBuilder.forPort(port)
//        .addService(ServerInterceptors.intercept(
//            new GreeterImpl(),
//            new ServerInterceptor() {
//              @Override
//              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
//                  ServerCall<ReqT, RespT> call,
//                  final Metadata requestHeaders,
//                  ServerCallHandler<ReqT, RespT> next) {
//                return next.startCall(call, requestHeaders);
//              }
//            }
//            )
//        )
//        .addService(new io.grpc.protobuf.ReflectionService(null))
//        .build();
    
    // This works with ReflectableServerBuilder.
    server = ProtoReflectableServerBuilder.forPort(port)
        .addService(ServerInterceptors.intercept(
            new GreeterImpl(),
            new ServerInterceptor() {
              @Override
              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                  ServerCall<ReqT, RespT> call,
                  final Metadata requestHeaders,
                  ServerCallHandler<ReqT, RespT> next) {
                return next.startCall(call, requestHeaders);
              }
            }
            )
        )
        .build();
    server.start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        HelloWorldServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final HelloWorldServer server = new HelloWorldServer();
    server.start();
    server.blockUntilShutdown();
  }

////  private class ProtobufMethodDescriptor<ReqT, RespT> extends MethodDescriptor<ReqT, RespT> {
////    private FileDescriptor fd;
////
////    @Override
////    public FileDescriptor getFileDescriptor() {
////      return fd;
////    }
////
/////*    public static <RequestT, ResponseT> ProtobufMethodDescriptor<RequestT, ResponseT> create(
////        MethodType type, String fullMethodName, Marshaller<RequestT> requestMarshaller,
////        Marshaller<ResponseT> responseMarshaller, FileDescriptor fd) {
////      return new ProtobufMethodDescriptor<RequestT, ResponseT>(
////          type, fullMethodName, requestMarshaller, responseMarshaller, false, false, fd);
////    }
////*/
////    protected ProtobufMethodDescriptor(MethodType type, String fullMethodName,
////      Marshaller<ReqT> requestMarshaller, Marshaller<RespT> responseMarshaller,
////      boolean idempotent, boolean safe, FileDescriptor fd) {
////      super(type, fullMethodName, requestMarshaller, responseMarshaller,
////          idempotent, safe);
////      this.fd = fd;
////    }
////  }
//
  private class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }

//    @Override
//    public void sayHelloAgain(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
//      HelloReply reply = HelloReply.newBuilder().setMessage("Hello again " + req.getName()).build();
//      responseObserver.onNext(reply);
//      responseObserver.onCompleted();
//    }
  }
//  
//  private class GreeterImpl extends GreeterGrpc.GreeterImplBase {
//
//    @Override
//    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
//      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
//      responseObserver.onNext(reply);
//      responseObserver.onCompleted();
//    }
//
////    @Override
////    public void sayHelloAgain(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
////      HelloReply reply = HelloReply.newBuilder().setMessage("Hello again " + req.getName()).build();
////      responseObserver.onNext(reply);
////      responseObserver.onCompleted();
////    }
//  }
  
//
//  public StreamObserver<ServerReflectionRequest> serverReflectionInfo(
//      Map<String, FileDescriptor> fileDescriptors,
//      final StreamObserver<ServerReflectionResponse> responseObserver) {
//    return new StreamObserver<ServerReflectionRequest>() {
//      @Override
//      public void onNext(ServerReflectionRequest request) {
//         switch (request.getMessageRequestCase()) {
//           case FILE_CONTAINING_SYMBOL:
//             getFileContainingSymbol(request);
//             break;
//           case FILE_BY_FILENAME:
//             getFileByName(request);
//             break;
//           case LIST_SERVICES:
//             /*
//             System.out.println("Request: " + request.toString());
//             System.out.println(request.getMessageRequestCase());
//             System.out.println(ServerReflectionRequest.MessageRequestCase.LIST_SERVICES);
//             System.out.println("LIST_SERVICES called (?)");
//             */
//             listServices(request);
//             break;
//           default:
//             sendErrorResponse(request, Status.INVALID_ARGUMENT,
//                 "You gave an invalid MessageRequest: " + request.getMessageRequestCase());
//         }
//      }
//
//      @Override
//      public void onCompleted() {
//        responseObserver.onCompleted();
//      }
//
//      @Override
//      public void onError(Throwable cause) {
//        responseObserver.onError(cause);
//      }
//
//      private void getFileContainingSymbol(ServerReflectionRequest request) {
//        String symbol = request.getFileContainingSymbol();
//        System.out.println("Looking for " + symbol);
//        for (FileDescriptor fd : fileDescriptors.values()) {
//          //com.google.protobuf.Descriptors.ServiceDescriptor sd = fd.findServiceByName(symbol);
//          // findServiceByName uses unqualified name
//          for (com.google.protobuf.Descriptors.ServiceDescriptor sd : fd.getServices()) {
//            if (sd.getFullName().equals(symbol)) {
//              System.out.println("Found it, " + sd.getFullName());
//              responseObserver.onNext(createServerReflectionResponse(request, fd));
//              return;
//            }
//          }
//        }
//        System.out.println("Not found");
//        sendErrorResponse(request, Status.NOT_FOUND, "unknown symbol: " + symbol);
//      }
//
//      private void getFileByName(ServerReflectionRequest request) {
//        /*
//        Map<String, FileDescriptor> fileDescriptors = new HashMap<String,
//            FileDescriptor>();
//        InternalHandlerRegistry registry = server.getRegistry();
//        Map<String, ServerServiceDefinition> services =
//          registry.getServices();
//        for (ServerServiceDefinition serviceDefinition : services.values()) {
//          for (ServerMethodDefinition<?, ?> methodDefinition :
//              serviceDefinition.getMethods()) {
//            MethodDescriptor<?, ?> md =
//                methodDefinition.getMethodDescriptor();
//            FileDescriptor methodFd = md.getFileDescriptor();
//            if (methodFd != null) {
//              fileDescriptors.put(methodFd.getName(), methodFd);
//            }
//          }
//        }
//        */
//
//        String name = request.getFileByFilename();
//        FileDescriptor fd = fileDescriptors.get(name);
//        if (fd != null) {
//          responseObserver.onNext(createServerReflectionResponse(request,
//                fd));
//        } else {
//          sendErrorResponse(request, Status.NOT_FOUND, "unknown filename: " + name);
//        }
//      }
//
//      private void listServices(ServerReflectionRequest request) {
//        /*
//        ListServiceResponse.Builder builder = ListServiceResponse.newBuilder();
//        InternalHandlerRegistry registry = server.getRegistry();
//        Map<String, ServerServiceDefinition> services =
//          registry.getServices();
//        for (String serviceName : services.keySet()) {
//          builder.addService(ServiceResponse.newBuilder().setName(serviceName));
//        }
//        */
//
//        /*
//        Map<String, FileDescriptor> fileDescriptors = new HashMap<String,
//            FileDescriptor>();
//        InternalHandlerRegistry registry = server.getRegistry();
//        Map<String, ServerServiceDefinition> services =
//          registry.getServices();
//        for (ServerServiceDefinition serviceDefinition : services.values()) {
//          for (ServerMethodDefinition<?, ?> methodDefinition :
//              serviceDefinition.getMethods()) {
//            MethodDescriptor<?, ?> md =
//                methodDefinition.getMethodDescriptor();
//            FileDescriptor methodFd = md.getFileDescriptor();
//            if (methodFd != null) {
//              System.out.println(methodFd.getName());
//              fileDescriptors.put(methodFd.getName(), methodFd);
//            }
//          }
//        }
//        */
//        // This approach only shows services that have .proto files - which
//        // makes sense, since the reflection API only works if it can export
//        // the proto file over the wire
//        // On the other hand, it's weird to not show services we know about just
//        // because they aren't reflectable
//        ListServiceResponse.Builder builder = ListServiceResponse.newBuilder();
//        for (FileDescriptor fd : fileDescriptors.values()) {
//          for (com.google.protobuf.Descriptors.ServiceDescriptor sd : fd.getServices()) {
//            builder.addService(ServiceResponse.newBuilder().setName(sd.getFullName()));
//          }
//        }
//        responseObserver.onNext(
//            ServerReflectionResponse.newBuilder()
//              .setValidHost(request.getHost())
//              .setOriginalRequest(request)
//              .setListServicesResponse(builder)
//              .build());
//      }
//
//      private void sendErrorResponse(ServerReflectionRequest request, Status status,
//          String message) {
//        ServerReflectionResponse response = ServerReflectionResponse.newBuilder()
//            .setValidHost(request.getHost())
//            .setOriginalRequest(request)
//            .setErrorResponse(
//                ErrorResponse.newBuilder()
//                    .setErrorCode(status.getCode().value())
//                    .setErrorMessage(message))
//            .build();
//        responseObserver.onNext(response);
//      }
//
//      private ServerReflectionResponse createServerReflectionResponse(
//          ServerReflectionRequest request, FileDescriptor fd) {
//        // get all fd dependencies
//        FileDescriptorResponse.Builder fdRBuilder = FileDescriptorResponse.newBuilder();
//        fdRBuilder.addFileDescriptorProto(fd.toProto().toByteString());
//        for (FileDescriptor dependencyFd : fd.getDependencies()) {
//          fdRBuilder.addFileDescriptorProto(dependencyFd.toProto().toByteString());
//        }
//        return ServerReflectionResponse.newBuilder()
//            .setValidHost(request.getHost())
//            .setOriginalRequest(request)
//            .setFileDescriptorResponse(fdRBuilder)
//            .build();
//      }
//
//    };
//  }
//
//  /*
//  private static class ReflectionFallbackRegistry extends HandlerRegistry {
//    private ServerServiceDefinition reflectionServiceDefinition;
//
//    public ReflectionFallbackRegistry(ServerServiceDefinition reflectionServiceDefinition) {
//      this.reflectionServiceDefinition = reflectionServiceDefinition;
//    }
//
//    @Override
//    public ServerMethodDefinition<?, ?> lookupMethod(String methodName,
//        @Nullable String authority) {
//      //if (methodName.equals("grpc.reflection.v1alpha.ServerReflection.ServerReflectionInfo")) {
//      return reflectionServiceDefinition.getMethod(methodName);
//      //}
//    }
//  }
//  */
//
//  private static class ReflectionMethodHandlers<Req, Resp> implements
//      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
//    private final HelloWorldServer hws;
//    private Map<String, FileDescriptor> fileDescriptors;
//
//    public ReflectionMethodHandlers(HelloWorldServer hws) {
//      this.hws = hws;
//    }
//
//    public void initFileDescriptors(Map<String, Message> protoMessages) {
//      ListServiceResponse.Builder builder = ListServiceResponse.newBuilder();
//      Map<String, FileDescriptor> fileDescriptors = new HashMap<String,
//          FileDescriptor>();
//      for (Message protoMessage : protoMessages.values()) {
//        FileDescriptor fd = protoMessage.getDescriptorForType().getFile();
//        if (fd != null) {
//          System.out.println(fd.getName());
//          fileDescriptors.put(fd.getName(), fd);
//        }
//      }
//      this.fileDescriptors = fileDescriptors;
//    }
//
//    @java.lang.Override
//    @java.lang.SuppressWarnings("unchecked")
//    public io.grpc.stub.StreamObserver<Req> invoke(io.grpc.stub.StreamObserver<Resp> responseObserver) {
//      return (io.grpc.stub.StreamObserver<Req>) hws.serverReflectionInfo(fileDescriptors,
//          (io.grpc.stub.StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse>) responseObserver);
//    }
//  }
//
//  private static class MethodHandlers<Req, Resp> implements
//      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp> {
////    private final HelloWorldServer hws;
////
////    public MethodHandlers(HelloWorldServer hws) {
////      this.hws = hws;
////    }
//
//    @java.lang.Override
//    @java.lang.SuppressWarnings("unchecked")
//    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
//      ((io.grpc.stub.StreamObserver<Empty>) responseObserver).onNext(Empty.getDefaultInstance());
//      System.out.println("Yoooooohooo!");
//      responseObserver.onCompleted();
////      hws.emptyCall((com.google.protobuf.EmptyProtos.Empty) request,
////        (io.grpc.stub.StreamObserver<com.google.protobuf.EmptyProtos.Empty>) responseObserver);
//    }
//  }
}
