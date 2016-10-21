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

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.ServiceDescriptor;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A builder to simplify the construction of a server implementing the Reflection API for protobuf
 * services. This builder automatically adds a {@link ProtoReflectionService} to the server and
 * registers all protobuf services with the reflection service.
 *
 * <p>This builder is designed to wrap another {@link ServerBuilder} and delegate to it for the
 * actual server creation.
 */
public final class ProtoReflectableServerBuilder
    extends ServerBuilder<ProtoReflectableServerBuilder> {
  private final ServerBuilder<?> wrappedServerBuilder;
  private final Set<ProtoFileDescriptorWrapper> protoFileDescriptorWrappers =
      new HashSet<ProtoFileDescriptorWrapper>();

  /**
   * Create a server builder that will bind to the given port. This wraps a call to {@link
   * ServerBuilder}.
   *
   * @param port the port on which the server is to be bound.
   * @return the server builder.
   */
  public static ProtoReflectableServerBuilder forPort(int port) {
    return new ProtoReflectableServerBuilder(ServerBuilder.forPort(port));
  }

  /**
   * Create a new ProtoReflectableServerBuilder that wraps the {@link ServerBuilder} instance.
   *
   * @param serverBuilder the server builder to use internally.
   * @return the server builder.
   */
  public static ProtoReflectableServerBuilder forBuilder(ServerBuilder<?> serverBuilder) {
    return new ProtoReflectableServerBuilder(serverBuilder);
  }

  /** Returns the wrapped {@link ServerBuilder} instance. */
  public ServerBuilder<?> getServerBuilder() {
    return wrappedServerBuilder;
  }

  private ProtoReflectableServerBuilder(ServerBuilder<?> serverBuilder) {
    this.wrappedServerBuilder = serverBuilder;
  }

  @Override
  public ProtoReflectableServerBuilder directExecutor() {
    wrappedServerBuilder.directExecutor();
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder executor(Executor executor) {
    wrappedServerBuilder.executor(executor);
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder addService(ServerServiceDefinition serviceDefinition) {
    ServiceDescriptor serviceDescriptor = serviceDefinition.getServiceDescriptor();
    if (serviceDescriptor.getAttachedObject() != null && serviceDescriptor.getAttachedObject()
            instanceof ProtoFileDescriptorWrapper) {
      protoFileDescriptorWrappers.add((ProtoFileDescriptorWrapper)
              serviceDescriptor.getAttachedObject());
    }
    wrappedServerBuilder.addService(serviceDefinition);
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder addService(BindableService bindableService) {
    return addService(bindableService.bindService());
  }

  @Override
  public ProtoReflectableServerBuilder addTransportFilter(ServerTransportFilter filter) {
    wrappedServerBuilder.addTransportFilter(filter);
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder fallbackHandlerRegistry(HandlerRegistry fallbackRegistry) {
    wrappedServerBuilder.fallbackHandlerRegistry(fallbackRegistry);
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder useTransportSecurity(File certChain, File privateKey) {
    wrappedServerBuilder.useTransportSecurity(certChain, privateKey);
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder decompressorRegistry(DecompressorRegistry registry) {
    wrappedServerBuilder.decompressorRegistry(registry);
    return this;
  }

  @Override
  public ProtoReflectableServerBuilder compressorRegistry(CompressorRegistry registry) {
    wrappedServerBuilder.compressorRegistry(registry);
    return this;
  }

  /*
   * Build the server. This delegates to {@link ServerBuilder}'s build() method, but first
   * instantiates a new {@link ProtoReflectionService} and adds it to the server builder.
   */
  @Override
  public Server build() {
    addService(new ProtoReflectionService(protoFileDescriptorWrappers));
    return wrappedServerBuilder.build();
  }
}
