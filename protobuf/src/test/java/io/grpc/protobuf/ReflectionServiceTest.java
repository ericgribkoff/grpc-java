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

import static org.junit.Assert.assertEquals;

import io.grpc.reflection.testing.ReflectableServiceGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.StreamRecorder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tests for {@link ReflectionService}. */
@RunWith(JUnit4.class)
public class ReflectionServiceTest {
  @Test
  public void listServicesTest() throws Exception {
    Set<ProtobufServiceDescriptor> protobufServiceDescriptors =
        new HashSet<ProtobufServiceDescriptor>();
    protobufServiceDescriptors.add(ReflectableServiceGrpc.getServiceDescriptor());
    protobufServiceDescriptors.add(ServerReflectionGrpc.getServiceDescriptor());
    ReflectionService reflectionService = new ReflectionService(protobufServiceDescriptors);

    final ServerReflectionRequest request =
        ServerReflectionRequest.newBuilder()
            .setHost("localhost")
            .setListServices("services")
            .build();

    final Set<ServiceResponse> goldenResponse =
        new HashSet<ServiceResponse>(Arrays.asList(
            ServiceResponse.newBuilder()
                .setName("grpc.reflection.v1alpha.ServerReflection")
                .build(),
            ServiceResponse.newBuilder()
                .setName("grpc.reflection.testing.ReflectableService")
                .build()));

    StreamRecorder<ServerReflectionResponse> responseObserver = StreamRecorder.create();
    StreamObserver<ServerReflectionRequest> requestObserver =
        reflectionService.serverReflectionInfo(responseObserver);
    requestObserver.onNext(request);
    requestObserver.onCompleted();
    Set<ServiceResponse> serviceResponseSet =
        new HashSet<ServiceResponse>(
            responseObserver.firstValue().get().getListServicesResponse().getServiceList());
    assertEquals(goldenResponse, serviceResponseSet);
    //    assertEquals("testing", responseObserver.firstValue().get().getListServicesResponse());
    //    assertEquals("testing", "");
    //    asyncStub.streamingOutputCall(request, recorder);
    //    recorder.awaitCompletion();
    //    assertSuccess(recorder);
    //
    assertEquals("a", "a");
  }
}

// put reflectable test service here?
