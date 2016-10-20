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

package io.grpc.protobuf.reflection;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class ProtoReflectionService extends ServerReflectionGrpc.ServerReflectionImplBase {
  private final Set<ProtoServiceDescriptor> protobufServiceDescriptors;
  private Set<String> serviceNames;
  private Map<String, FileDescriptor> fileDescriptorsByName;
  private Map<String, FileDescriptor> fileDescriptorsBySymbol;
  private Map<String, Map<Integer, FileDescriptor>> fileDescriptorsByExtensionAndNumber;
  private Boolean mapsInitialized = false;

  public ProtoReflectionService(Set<ProtoServiceDescriptor> protobufServiceDescriptors) {
    this.protobufServiceDescriptors = protobufServiceDescriptors;
  }

  private void processExtension(FieldDescriptor extension, FileDescriptor fd) {
    String extensionName = extension.getContainingType().getFullName();
    int extensionNumber = extension.getNumber();
    if (fileDescriptorsByExtensionAndNumber.containsKey(extensionName)) {
      fileDescriptorsByExtensionAndNumber.get(extensionName).put(extensionNumber, fd);
    } else {
      Map<Integer, FileDescriptor> extensionMap = new HashMap<Integer, FileDescriptor>();
      extensionMap.put(extensionNumber, fd);
      fileDescriptorsByExtensionAndNumber.put(extensionName, extensionMap);
    }
  }

  private void processService(ServiceDescriptor service, FileDescriptor fd) {
    fileDescriptorsBySymbol.put(service.getFullName(), fd);
    for (MethodDescriptor method : service.getMethods()) {
      fileDescriptorsBySymbol.put(method.getFullName(), fd);
    }
  }

  private void processType(Descriptor type, FileDescriptor fd) {
    fileDescriptorsBySymbol.put(type.getFullName(), fd);
    for (FieldDescriptor extension : type.getExtensions()) {
      processExtension(extension, fd);
    }
    for (Descriptor nestedType : type.getNestedTypes()) {
      processType(nestedType, fd);
    }
  }

  private void processFileDescriptor(FileDescriptor fd) {
    fileDescriptorsByName.put(fd.getName(), fd);
    for (ServiceDescriptor service : fd.getServices()) {
      processService(service, fd);
    }
    for (Descriptor type : fd.getMessageTypes()) {
      processType(type, fd);
    }
    for (FieldDescriptor extension : fd.getExtensions()) {
      processExtension(extension, fd);
    }
  }

  private synchronized void initFileDescriptorMaps() {
    if (mapsInitialized) {
      return;
    }

    serviceNames = new HashSet<String>();
    fileDescriptorsByName = new HashMap<String, FileDescriptor>();
    fileDescriptorsBySymbol = new HashMap<String, FileDescriptor>();
    fileDescriptorsByExtensionAndNumber = new HashMap<String, Map<Integer, FileDescriptor>>();
    Queue<FileDescriptor> fileDescriptorsToProcess = new LinkedList<FileDescriptor>();
    for (ProtoServiceDescriptor serviceDescriptor : protobufServiceDescriptors) {
      serviceNames.add(serviceDescriptor.getName());
      fileDescriptorsToProcess.offer(serviceDescriptor.getFile());
    }
    while (!fileDescriptorsToProcess.isEmpty()) {
      FileDescriptor currentFd = fileDescriptorsToProcess.poll();
      processFileDescriptor(currentFd);
      for (FileDescriptor dependencyFd : currentFd.getDependencies()) {
        if (!fileDescriptorsByName.containsKey(dependencyFd.getName())) {
          fileDescriptorsToProcess.offer(dependencyFd);
        }
      }
    }
    mapsInitialized = true;
  }

  @Override
  public StreamObserver<ServerReflectionRequest> serverReflectionInfo(
      final StreamObserver<ServerReflectionResponse> responseObserver) {
    initFileDescriptorMaps();
    return new StreamObserver<ServerReflectionRequest>() {

      @Override
      public void onNext(ServerReflectionRequest request) {
        switch (request.getMessageRequestCase()) {
          case FILE_BY_FILENAME:
            getFileByName(request);
            break;
          case FILE_CONTAINING_SYMBOL:
            getFileContainingSymbol(request);
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
            sendErrorResponse(request, Status.UNIMPLEMENTED, "");
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

      private void getFileByName(ServerReflectionRequest request) {
        String name = request.getFileByFilename();
        FileDescriptor fd = fileDescriptorsByName.get(name);
        if (fd != null) {
          responseObserver.onNext(createServerReflectionResponse(request, fd));
        } else {
          sendErrorResponse(request, Status.NOT_FOUND, "File not found.");
        }
      }

      private void getFileContainingSymbol(ServerReflectionRequest request) {
        String symbol = request.getFileContainingSymbol();
        if (fileDescriptorsBySymbol.containsKey(symbol)) {
          FileDescriptor fd = fileDescriptorsBySymbol.get(symbol);
          responseObserver.onNext(createServerReflectionResponse(request, fd));
          return;
        }
        sendErrorResponse(request, Status.NOT_FOUND, "Symbol not found.");
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
          sendErrorResponse(request, Status.NOT_FOUND, "Extension not found.");
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

      private ServerReflectionResponse createServerReflectionResponse(
          ServerReflectionRequest request, FileDescriptor fd) {
        FileDescriptorResponse.Builder fdRBuilder = FileDescriptorResponse.newBuilder();

        Set<String> seenFiles = new HashSet<String>();
        Queue<FileDescriptor> frontier = new LinkedList<FileDescriptor>();
        frontier.offer(fd);
        while (!frontier.isEmpty()) {
          FileDescriptor nextFd = frontier.poll();
          seenFiles.add(nextFd.getName());
          fdRBuilder.addFileDescriptorProto(nextFd.toProto().toByteString());
          for (FileDescriptor dependencyFd : nextFd.getDependencies()) {
            if (!seenFiles.contains(dependencyFd.getName())) {
              frontier.offer(dependencyFd);
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
