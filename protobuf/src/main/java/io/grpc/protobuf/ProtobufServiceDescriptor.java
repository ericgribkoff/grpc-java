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

import com.google.protobuf.Descriptors.FileDescriptor;

import io.grpc.AbstractServiceDescriptor;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;

import java.util.Collection;

public abstract class ProtobufServiceDescriptor extends AbstractServiceDescriptor {
  private final ServiceDescriptor wrappedServiceDescriptor;

  public ProtobufServiceDescriptor(String name, MethodDescriptor<?, ?>... methods) {
    wrappedServiceDescriptor = new ServiceDescriptor(name, methods);
  }

  public ProtobufServiceDescriptor(String name, Collection<MethodDescriptor<?, ?>> methods) {
    wrappedServiceDescriptor = new ServiceDescriptor(name, methods);
  }

  /** Simple name of the service. It is not an absolute path. */
  @Override
  public String getName() {
    return wrappedServiceDescriptor.getName();
  }

  /**
   * A collection of {@link MethodDescriptor} instances describing the methods exposed by the
   * service.
   */
  @Override
  public Collection<MethodDescriptor<?, ?>> getMethods() {
    return wrappedServiceDescriptor.getMethods();
  }
  
  public abstract FileDescriptor getFile();
}
