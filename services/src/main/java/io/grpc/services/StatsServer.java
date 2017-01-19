/*
 * Copyright 2017, Google Inc. All rights reserved.
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

package io.grpc.services;

import io.grpc.Server;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.service.ProtoReflectionService;

import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

public final class StatsServer {
  private static final Logger logger = Logger.getLogger(StatsServer.class.getName());
  private static StatsServer instance;

  private final boolean usingSharedExecutor;

  private Server server;
  private Executor executor;
  private ScheduledExecutorService scheduledExecutor;

  private StatsServer(Executor executor) throws Exception {
    if (executor == null) {
      usingSharedExecutor = true;
      this.executor = SharedResourceHolder.get(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
    } else {
      usingSharedExecutor = false;
      this.executor = executor;
    };

    int port = 50052; //TODO(ericgribkoff) choose port/address for UDS/Named Pipes
    try {
      server = NettyServerBuilder.forAddress(new DomainSocketAddress("/tmp/test.sock"))
        .channelType(EpollServerDomainSocketChannel.class)
        .bossEventLoopGroup(new EpollEventLoopGroup())
        .workerEventLoopGroup(new EpollEventLoopGroup())
        .executor(executor)
        .addService(StatsServiceImpl.getInstance())
        .addService(ProtoReflectionService.getInstance())
        .build()
        .start();
    } catch (Exception e) {
      //TODO(ericgribkoff) do something
      //logger.info("Server failed to start");
      //logger.info(e.getMessage());
      //return;
      throw e;
    }
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        StatsServer.this.stop();
        System.err.println("*** server shut down");
      }
    });

  }

  //TODO(ericgribkoff) Make this more performant
  /** Gets the canonical instance of the server. Created with an executor.*/ 
  public static synchronized void startServer(Executor executor) throws Exception {
    if (instance == null) {
      instance = new StatsServer(executor);
    }
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }
}
