/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.protobuf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import io.grpc.AbstractServiceDescriptor;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.reflection.v1alpha.ErrorResponse;
import io.grpc.reflection.v1alpha.ExtensionNumberResponse;
import io.grpc.reflection.v1alpha.ExtensionRequest;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ListServiceResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;

public final class ReflectableServerBuilder extends ServerBuilder<ReflectableServerBuilder> {
  private final ServerBuilder<?> wrappedServerBuilder;
  private final Map<String, ProtobufServiceDescriptor> protobufServiceDescriptors =
      new HashMap<String, ProtobufServiceDescriptor>();

  public static ReflectableServerBuilder forPort(int port) {
    return new ReflectableServerBuilder(ServerBuilder.forPort(port));
  }

  public static ReflectableServerBuilder forBuilder(ServerBuilder<?> serverBuilder) {
    return new ReflectableServerBuilder(serverBuilder);
  }

  private ReflectableServerBuilder(ServerBuilder<?> serverBuilder) {
    this.wrappedServerBuilder = serverBuilder;
  }

  @Override
  public final ReflectableServerBuilder directExecutor() {
    wrappedServerBuilder.directExecutor();
    return this;
  }

  @Override
  public final ReflectableServerBuilder executor(Executor executor) {
    wrappedServerBuilder.executor(executor);
    return this;
  }

  //  /** Capture a reference to ProtobufServerServiceDefinitions. */
  //  public final ReflectableServerBuilder addService(ProtobufServerServiceDefinition service) {
  //    wrappedServerBuilder.addService(service);
  //    protobufServiceDefinitions.put(service.getServiceDescriptor().getName(), service);
  //    System.out.println("Size: " + protobufServiceDefinitions.size());
  //    return this;
  //  }

  @Override
  public final ReflectableServerBuilder addService(ServerServiceDefinition serviceDefinition) {
    AbstractServiceDescriptor serviceDescriptor = serviceDefinition.getServiceDescriptor();
    if (serviceDescriptor instanceof ProtobufServiceDescriptor) {
      System.out.println("Adding reflectable " + serviceDescriptor.getName());
      protobufServiceDescriptors.put(
          serviceDescriptor.getName(), (ProtobufServiceDescriptor) serviceDescriptor);
    } else {
      System.out.println("Adding non-reflectable " + serviceDescriptor.getName());
    }
    wrappedServerBuilder.addService(serviceDefinition);
    return this;
  }

  //  /** Capture a reference to ProtobufServerServiceDefinitions. */
  //  public final ReflectableServerBuilder addService(ProtobufBindableService bindableService) {
  //    System.out.println("Have to bind a service");
  //    System.out.println(bindableService.bindService().getClass());
  //    return addService(bindableService.bindService());
  //  }

  @Override
  public final ReflectableServerBuilder addService(BindableService bindableService) {
    return addService(bindableService.bindService());
  }

  @Override
  public final ReflectableServerBuilder addTransportFilter(ServerTransportFilter filter) {
    wrappedServerBuilder.addTransportFilter(filter);
    return this;
  }

  @Override
  public final ReflectableServerBuilder fallbackHandlerRegistry(HandlerRegistry fallbackRegistry) {
    wrappedServerBuilder.fallbackHandlerRegistry(fallbackRegistry);
    return this;
  }

  @Override
  public final ReflectableServerBuilder useTransportSecurity(File certChain, File privateKey) {
    wrappedServerBuilder.useTransportSecurity(certChain, privateKey);
    return this;
  }

  @Override
  public final ReflectableServerBuilder decompressorRegistry(DecompressorRegistry registry) {
    wrappedServerBuilder.decompressorRegistry(registry);
    return this;
  }

  @Override
  public final ReflectableServerBuilder compressorRegistry(CompressorRegistry registry) {
    wrappedServerBuilder.compressorRegistry(registry);
    return this;
  }

  @Override
  public Server build() {
    System.out.println("Building");
    ReflectionImpl reflectionImpl = new ReflectionImpl();
    addService(reflectionImpl);
    Server server = wrappedServerBuilder.build();
    reflectionImpl.initFileDescriptors(protobufServiceDescriptors);
    return server;
  }

  private class ReflectionImpl extends ServerReflectionGrpc.ServerReflectionImplBase {
    private Set<String> serviceNames = new HashSet<String>();
    private Map<String, FileDescriptor> fileDescriptorsByName =
        new HashMap<String, FileDescriptor>();
    private Map<String, FileDescriptor> fileDescriptorsBySymbol =
        new HashMap<String, FileDescriptor>();
    private Map<String, Map<Integer, FileDescriptor>> fileDescriptorsByExtensionAndNumber =
        new HashMap<String, Map<Integer, FileDescriptor>>();

    private void processFileDescriptor(FileDescriptor fd) {
      System.out.println("File: " + fd.getName() + " with services:");
      fileDescriptorsByName.put(fd.getName(), fd);
      for (ServiceDescriptor sd : fd.getServices()) {
        System.out.println("Service: " + sd.getFullName() + " (" + sd.getName() + ")");
        fileDescriptorsBySymbol.put(sd.getFullName(), fd);
        for (MethodDescriptor md : sd.getMethods()) {
          System.out.println("Method: " + md.getFullName() + " (" + md.getName() + ")");
          fileDescriptorsBySymbol.put(md.getFullName(), fd);
        }
      }
      for (Descriptor d : fd.getMessageTypes()) {
        System.out.println("Message: " + d.getFullName() + " (" + d.getName() + ")");
        fileDescriptorsBySymbol.put(d.getFullName(), fd);
        
        for (FieldDescriptor f : d.getExtensions()) {
          String extensionName = f.getContainingType().getFullName();
          int extensionNumber = f.getNumber();
          if (fileDescriptorsByExtensionAndNumber.containsKey(extensionName)) {
            fileDescriptorsByExtensionAndNumber.get(extensionName).put(extensionNumber, fd);
          } else {
            Map<Integer, FileDescriptor> extensionMap = new HashMap<Integer, FileDescriptor>();
            extensionMap.put(extensionNumber, fd);
            fileDescriptorsByExtensionAndNumber.put(extensionName, extensionMap);
          }
          System.out.println("Field descriptor: " + f.getFullName() + " (" + f.getName() + ")");
          System.out.println(extensionName + " : " + extensionNumber);
        }
        
        // BFS over nested descriptor types
        Queue<Descriptor> frontier = new LinkedList<Descriptor>(d.getNestedTypes());
        while (!frontier.isEmpty()) {
          Descriptor curD = frontier.poll();
          fileDescriptorsBySymbol.put(curD.getFullName(), fd);
          System.out.println("Nested Message: " + curD.getFullName() + " (" + curD.getName() + ")");
          for (FieldDescriptor f : curD.getExtensions()) {
            String extensionName = f.getContainingType().getFullName();
            int extensionNumber = f.getNumber();
            if (fileDescriptorsByExtensionAndNumber.containsKey(extensionName)) {
              fileDescriptorsByExtensionAndNumber.get(extensionName).put(extensionNumber, fd);
            } else {
              Map<Integer, FileDescriptor> extensionMap = new HashMap<Integer, FileDescriptor>();
              extensionMap.put(extensionNumber, fd);
              fileDescriptorsByExtensionAndNumber.put(extensionName, extensionMap);
            }
            System.out.println("Field descriptor: " + f.getFullName() + " (" + f.getName() + ")");
            System.out.println(extensionName + " : " + extensionNumber);
          }
          
          for (Descriptor nextD : curD.getNestedTypes()) {
             frontier.offer(nextD);
          }
        }
      }
      for (FieldDescriptor f : fd.getExtensions()) {
        String extensionName = f.getContainingType().getFullName();
        int extensionNumber = f.getNumber();
        if (fileDescriptorsByExtensionAndNumber.containsKey(extensionName)) {
          fileDescriptorsByExtensionAndNumber.get(extensionName).put(extensionNumber, fd);
        } else {
          Map<Integer, FileDescriptor> extensionMap = new HashMap<Integer, FileDescriptor>();
          extensionMap.put(extensionNumber, fd);
          fileDescriptorsByExtensionAndNumber.put(extensionName, extensionMap);
        }
        System.out.println("Field descriptor: " + f.getFullName() + " (" + f.getName() + ")");
        System.out.println(extensionName + " : " + extensionNumber);
      }
      System.out.println("");
    }

    //TODO(ericgribkoff) Make this lazy
    public void initFileDescriptors(
        Map<String, ProtobufServiceDescriptor> protobufServiceDescriptors) {

      for (ProtobufServiceDescriptor serviceDescriptor : protobufServiceDescriptors.values()) {
        serviceNames.add(serviceDescriptor.getName());
        processFileDescriptor(serviceDescriptor.getFile());
      }

      // BFS over file descriptor dependencies
      Queue<FileDescriptor> frontier =
          new LinkedList<FileDescriptor>(fileDescriptorsByName.values());
      while (!frontier.isEmpty()) {
        FileDescriptor fd = frontier.poll();
        for (FileDescriptor dependencyFd : fd.getDependencies()) {
          //          System.out.println(dependencyFd.getName() + " is a dependency of " + fd.getName());
          // Protoc doesn't allow circular imports/dependencies, but just in case check?
          if (!fileDescriptorsByName.containsKey(dependencyFd.getName())) {
            processFileDescriptor(dependencyFd);
            frontier.offer(dependencyFd);
          }
        }
      }
    }

    @Override
    public StreamObserver<ServerReflectionRequest> serverReflectionInfo(
        final StreamObserver<ServerReflectionResponse> responseObserver) {
      return new StreamObserver<ServerReflectionRequest>() {
        @Override
        public void onNext(ServerReflectionRequest request) {
          switch (request.getMessageRequestCase()) {
            case FILE_CONTAINING_SYMBOL:
              getFileContainingSymbol(request);
              break;
            case FILE_BY_FILENAME:
              getFileByName(request);
              break;
            case FILE_CONTAINING_EXTENSION:
              getFileByExtension(request);
              break;
            case ALL_EXTENSION_NUMBERS_OF_TYPE:
              getAllExtensions(request);
              break;
            case LIST_SERVICES:
              listServices(request);
              break;
            default:
              sendErrorResponse(
                  request,
                  Status.INVALID_ARGUMENT,
                  "You gave an invalid MessageRequest: " + request.getMessageRequestCase());
          }
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }

        @Override
        public void onError(Throwable cause) {
          responseObserver.onError(cause);
        }

        private void getFileContainingSymbol(ServerReflectionRequest request) {
          String symbol = request.getFileContainingSymbol();
          if (fileDescriptorsBySymbol.containsKey(symbol)) {
            FileDescriptor fd = fileDescriptorsBySymbol.get(symbol);
            responseObserver.onNext(createServerReflectionResponse(request, fd));
            return;
          }
          sendErrorResponse(request, Status.NOT_FOUND, "unknown symbol: " + symbol);
        }

        private void getFileByName(ServerReflectionRequest request) {
          String name = request.getFileByFilename();
          FileDescriptor fd = fileDescriptorsByName.get(name);
          if (fd != null) {
            responseObserver.onNext(createServerReflectionResponse(request, fd));
          } else {
            sendErrorResponse(request, Status.NOT_FOUND, "unknown filename: " + name);
          }
        }

        private void getFileByExtension(ServerReflectionRequest request) {
          ExtensionRequest ext = request.getFileContainingExtension();
          String containingType = ext.getContainingType();
          int extensionNumber = ext.getExtensionNumber();
          if (fileDescriptorsByExtensionAndNumber.containsKey(containingType)
              && fileDescriptorsByExtensionAndNumber
                  .get(containingType)
                  .containsKey(extensionNumber)) {
            responseObserver.onNext(
                createServerReflectionResponse(
                    request,
                    fileDescriptorsByExtensionAndNumber.get(containingType).get(extensionNumber)));
          } else {
            sendErrorResponse(
                request,
                Status.NOT_FOUND,
                "unknown type name: "
                    + ext.getContainingType()
                    + " ,or extension number: "
                    + ext.getExtensionNumber());
          }
        }

        private void getAllExtensions(ServerReflectionRequest request) {
          String type = request.getAllExtensionNumbersOfType();
          ExtensionNumberResponse.Builder builder =
              ExtensionNumberResponse.newBuilder().setBaseTypeName(type);

          if (fileDescriptorsByExtensionAndNumber.containsKey(type)) {
            for (int extensionNumber : fileDescriptorsByExtensionAndNumber.get(type).keySet()) {
              builder.addExtensionNumber(extensionNumber);
            }
            responseObserver.onNext(
                ServerReflectionResponse.newBuilder()
                    .setValidHost(request.getHost())
                    .setOriginalRequest(request)
                    .setAllExtensionNumbersResponse(builder)
                    .build());
          } else {
            sendErrorResponse(request, Status.NOT_FOUND, "Type not found.");
          }
        }

        private void listServices(ServerReflectionRequest request) {
          ListServiceResponse.Builder builder = ListServiceResponse.newBuilder();
          for (String serviceName : serviceNames) {
            builder.addService(ServiceResponse.newBuilder().setName(serviceName));
          }

          responseObserver.onNext(
              ServerReflectionResponse.newBuilder()
                  .setValidHost(request.getHost())
                  .setOriginalRequest(request)
                  .setListServicesResponse(builder)
                  .build());
        }

        private void sendErrorResponse(
            ServerReflectionRequest request, Status status, String message) {
          ServerReflectionResponse response =
              ServerReflectionResponse.newBuilder()
                  .setValidHost(request.getHost())
                  .setOriginalRequest(request)
                  .setErrorResponse(
                      ErrorResponse.newBuilder()
                          .setErrorCode(status.getCode().value())
                          .setErrorMessage(message))
                  .build();
          responseObserver.onNext(response);
        }

        //TODO(ericgribkoff) These should be streamed back to the client
        private ServerReflectionResponse createServerReflectionResponse(
            ServerReflectionRequest request, FileDescriptor fd) {
          FileDescriptorResponse.Builder fdRBuilder = FileDescriptorResponse.newBuilder();
          fdRBuilder.addFileDescriptorProto(fd.toProto().toByteString());

          // get all fd dependencies
          // BFS over file descriptor dependencies
          //TODO(ericgribkoff) Refactor/combine with BFS in init
          Queue<FileDescriptor> frontier = new LinkedList<FileDescriptor>();
          frontier.offer(fd);
          Set<String> sentDescriptors = new HashSet<String>();
          sentDescriptors.add(fd.getName());
          while (!frontier.isEmpty()) {
            FileDescriptor nextFd = frontier.poll();
            for (FileDescriptor dependencyFd : nextFd.getDependencies()) {
              //System.out.println(dependencyFd.getName() + " is a dependency of " + fd.getName());
              // Protoc doesn't allow circular imports/dependencies, but just in case check?
              // Also, according to spec, here we can avoid sending the same proto file twice
              if (!sentDescriptors.contains(dependencyFd.getName())) {
                sentDescriptors.add(dependencyFd.getName());
                frontier.offer(dependencyFd);
                fdRBuilder.addFileDescriptorProto(dependencyFd.toProto().toByteString());
              }
            }
          }
          return ServerReflectionResponse.newBuilder()
              .setValidHost(request.getHost())
              .setOriginalRequest(request)
              .setFileDescriptorResponse(fdRBuilder)
              .build();
        }
      };
    }
  }
}
