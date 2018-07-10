/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.helloworld;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A simple client that requests a greeting from the {@link HelloWorldServer}.
 */
public class HelloWorldClient {
  private final ExecutorService executor = Executors.newFixedThreadPool(10);
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext()
        .build());
  }

  /** Construct client for accessing HelloWorld server using the existing channel. */
  HelloWorldClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
//    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    channel.shutdownNow();
  }

  /** Say hello to server. */
  public boolean greet(String name) {
//    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    CountDownLatch latch = new CountDownLatch(1);
    try {
      // Lambda Runnable
      Runnable task = () -> {
        channel.enterIdle();
        latch.countDown();
      };

//      executor.execute(task);
      new Thread(task).start();
      response = blockingStub.sayHello(request);
      latch.await(1, TimeUnit.SECONDS);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return false;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
//    logger.info("Greeting: " + response.getMessage());
    return true;
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting.
   */
  public static void main(String[] args) throws Exception {
    int total = 100000;
    int success = 0;
    for (int i = 0; i < total; i++) {
      HelloWorldClient client = new HelloWorldClient("localhost", 50051);
      try {
        /* Access a service running on the local machine on port 50051 */
        String user = "world";
        if (args.length > 0) {
          user = args[0]; /* Use the arg as the name to greet if provided */
        }
        if (client.greet(user)) {
          success++;
        }
        if (i % 100 == 0) {
          System.out.println("Success: " + success + " So far: " + (i+1));
        }
      } finally {
        client.shutdown();
      }
    }
    System.out.println("Success: " + success + " Total: " + total);
  }
}
