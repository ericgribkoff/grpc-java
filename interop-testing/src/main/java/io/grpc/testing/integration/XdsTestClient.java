/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.testing.integration;

import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.integration.Messages.LoadBalancerStatsRequest;
import io.grpc.testing.integration.Messages.LoadBalancerStatsResponse;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Client for xDS interop tests. */
public final class XdsTestClient {
  private static Logger logger = Logger.getLogger(XdsTestClient.class.getName());

  private final Set<XdsStatsWatcher> watchers = new HashSet<>();
  private final Object lock = new Object();
  private final List<ManagedChannel> channels = new ArrayList<>();

  private int numChannels = 1;
  private boolean printResponse = false;
  private int qps = 1;
  private List<RpcType> rpc = ImmutableList.of(RpcType.UNARY_CALL);
  private Map<RpcType, Metadata> metadata = new HashMap<>();
  private int rpcTimeoutSec = 2;
  private String server = "localhost:8080";
  private int statsPort = 8081;
  private Server statsServer;
  private long currentRequestId;
  private ListeningScheduledExecutorService exec;

  private enum RpcType {
    EMPTY_CALL,
    UNARY_CALL;

    public String toCamelCase() {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, toString());
    }
  }

  /**
   * The main application allowing this client to be launched from the command line.
   */
  public static void main(String[] args) {
    final XdsTestClient client = new XdsTestClient();
    client.parseArgs(args);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              @SuppressWarnings("CatchAndPrintStackTrace")
              public void run() {
                try {
                  client.stop();
                } catch (Exception e) {
                  e.printStackTrace();
                }
              }
            });
    client.run();
  }

  private void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("metadata".equals(key)) {
        metadata = parseMetadata(value);
      } else if ("num_channels".equals(key)) {
        numChannels = Integer.valueOf(value);
      } else if ("print_response".equals(key)) {
        printResponse = Boolean.valueOf(value);
      } else if ("qps".equals(key)) {
        qps = Integer.valueOf(value);
      } else if ("rpc".equals(key)) {
        rpc = parseRpcs(value);
      } else if ("rpc_timeout_sec".equals(key)) {
        rpcTimeoutSec = Integer.valueOf(value);
      } else if ("server".equals(key)) {
        server = value;
      } else if ("stats_port".equals(key)) {
        statsPort = Integer.valueOf(value);
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }

    if (usage) {
      XdsTestClient c = new XdsTestClient();
      System.err.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --num_channels=INT     Default: "
              + c.numChannels
              + "\n  --print_response=BOOL  Write RPC response to stdout. Default: "
              + c.printResponse
              + "\n  --qps=INT              Qps per channel, for each type of RPC. Default: "
              + c.qps
              + "\n  --rpc=STR              Types of RPCs to make, ',' separated string. RPCs can "
              + "be EmptyCall or UnaryCall."
              + "\n  --metadata=STR         The metadata to send with each RPC, in the format "
              + "EmptyCall:key1:value1,UnaryCall:key2:value2."
              + "\n  --rpc_timeout_sec=INT  Per RPC timeout seconds. Default: "
              + c.rpcTimeoutSec
              + "\n  --server=host:port     Address of server. Default: "
              + c.server
              + "\n  --stats_port=INT       Port to expose peer distribution stats service. "
              + "Default: "
              + c.statsPort);
      System.exit(1);
    }
  }

  private static List<RpcType> parseRpcs(String rpcArg) {
    List<RpcType> rpcs = new ArrayList<>();
    for (String rpc : Splitter.on(',').split(rpcArg)) {
      rpcs.add(parseRpc(rpc));
    }
    return rpcs;
  }

  private static Map<RpcType, Metadata> parseMetadata(String metadataArg) {
    Map<RpcType, Metadata> rpcMetadata = new HashMap<>();
    for (String metadata : Splitter.on(',').split(metadataArg)) {
      List<String> parts = Splitter.on(':').splitToList(metadata);
      if (parts.size() != 3) {
        throw new IllegalArgumentException("Invalid metadata: '" + metadata + "'");
      }
      RpcType rpc = parseRpc(parts.get(0));
      String key = parts.get(1);
      String value = parts.get(2);
      Metadata md = new Metadata();
      md.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
      if (rpcMetadata.containsKey(rpc)) {
        rpcMetadata.get(rpc).merge(md);
      } else {
        rpcMetadata.put(rpc, md);
      }
    }
    return rpcMetadata;
  }

  private static RpcType parseRpc(String rpc) {
    if ("EmptyCall".equals(rpc)) {
      return RpcType.EMPTY_CALL;
    } else if ("UnaryCall".equals(rpc)) {
      return RpcType.UNARY_CALL;
    } else {
      throw new IllegalArgumentException("Unknown RPC: '" + rpc + "'");
    }
  }

  private void run() {
    statsServer = NettyServerBuilder.forPort(statsPort).addService(new XdsStatsImpl()).build();
    try {
      statsServer.start();
      for (int i = 0; i < numChannels; i++) {
        channels.add(NettyChannelBuilder.forTarget(server).usePlaintext().build());
      }
      exec = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor());
      runQps();
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Error running client", t);
      System.exit(1);
    }
  }

  private void stop() throws InterruptedException {
    if (statsServer != null) {
      statsServer.shutdownNow();
      if (!statsServer.awaitTermination(5, TimeUnit.SECONDS)) {
        System.err.println("Timed out waiting for server shutdown");
      }
    }
    for (ManagedChannel channel : channels) {
      channel.shutdownNow();
    }
    if (exec != null) {
      exec.shutdownNow();
    }
  }


  private void runQps() throws InterruptedException, ExecutionException {
    final SettableFuture<Void> failure = SettableFuture.create();
    final class PeriodicRpc implements Runnable {
      private final RpcType rpcType;
      // private final MethodDescriptor<?,?> method;

      private PeriodicRpc(RpcType rpcType) {
        // if (rpcType == RpcType.EMPTY_CALL) {
        //   this.method = TestServiceGrpc.getEmptyCallMethod();
        // } else if (rpcType == RpcType.UNARY_CALL) {
        //   this.method = TestServiceGrpc.getUnaryCallMethod();
        // }
        this.rpcType = rpcType;
      }

      @Override
      public void run() {
        final long requestId;
        final Set<XdsStatsWatcher> savedWatchers = new HashSet<>();
        synchronized (lock) {
          currentRequestId += 1;
          requestId = currentRequestId;
          savedWatchers.addAll(watchers);
        }

        if (rpcType == RpcType.EMPTY_CALL) {
          ManagedChannel channel = channels.get((int) (requestId % channels.size()));
          final ClientCall<EmptyProtos.Empty, EmptyProtos.Empty> call =
              channel.newCall(
                  TestServiceGrpc.getEmptyCallMethod(),
                  CallOptions.DEFAULT.withDeadlineAfter(rpcTimeoutSec, TimeUnit.SECONDS));
          call.start(
              new ClientCall.Listener<EmptyProtos.Empty>() {
                private String hostname;

                // @Override
                // public void onMessage(Empty response) {
                // }

                @Override
                public void onClose(Status status, Metadata trailers) {
                  if (printResponse && !status.isOk()) {
                    logger.log(Level.WARNING, "Greeting RPC failed with status {0}", status);
                  }
                  for (XdsStatsWatcher watcher : savedWatchers) {
                    watcher.rpcCompleted(rpcType.toCamelCase(), requestId, hostname);
                  }
                }
              },
              metadata.get(rpcType));

          call.sendMessage(EmptyProtos.Empty.getDefaultInstance());
          call.request(1);
          call.halfClose();
        } else if (rpcType == RpcType.UNARY_CALL) {
          SimpleRequest request = SimpleRequest.newBuilder().setFillServerId(true).build();
          ManagedChannel channel = channels.get((int) (requestId % channels.size()));
          final ClientCall<SimpleRequest, SimpleResponse> call =
              channel.newCall(
                  TestServiceGrpc.getUnaryCallMethod(),
                  CallOptions.DEFAULT.withDeadlineAfter(rpcTimeoutSec, TimeUnit.SECONDS));
          call.start(
              new ClientCall.Listener<SimpleResponse>() {
                private String hostname;

                @Override
                public void onMessage(SimpleResponse response) {
                  hostname = response.getHostname();
                  // TODO(ericgribkoff) Currently some test environments cannot access the stats RPC
                  // service and rely on parsing stdout.
                  if (printResponse) {
                    System.out.println(
                        "Greeting: Hello world, this is "
                            + response.getHostname()
                            + ", from "
                            + call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
                  }
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                  if (printResponse && !status.isOk()) {
                    logger.log(Level.WARNING, "Greeting RPC failed with status {0}", status);
                  }
                  for (XdsStatsWatcher watcher : savedWatchers) {
                    watcher.rpcCompleted(rpcType.toCamelCase(), requestId, hostname);
                  }
                }
              },
              metadata.get(rpcType));

          call.sendMessage(request);
          call.request(1);
          call.halfClose();
        }
      }
    }

    long nanosPerQuery = TimeUnit.SECONDS.toNanos(1) / qps;

    for (RpcType rpcType : rpc) {
      ListenableScheduledFuture<?> future =
          exec.scheduleAtFixedRate(
              new PeriodicRpc(rpcType), 0, nanosPerQuery, TimeUnit.NANOSECONDS);

      Futures.addCallback(
          future,
          new FutureCallback<Object>() {

            @Override
            public void onFailure(Throwable t) {
              failure.setException(t);
            }

            @Override
            public void onSuccess(Object o) {}
          },
          MoreExecutors.directExecutor());
    }

    failure.get();
  }

  private class XdsStatsImpl extends LoadBalancerStatsServiceGrpc.LoadBalancerStatsServiceImplBase {
    @Override
    public void getClientStats(
        LoadBalancerStatsRequest req, StreamObserver<LoadBalancerStatsResponse> responseObserver) {
      XdsStatsWatcher watcher;
      synchronized (lock) {
        long startId = currentRequestId + 1;
        long endId = startId + req.getNumRpcs();
        watcher = new XdsStatsWatcher(startId, endId);
        watchers.add(watcher);
      }
      LoadBalancerStatsResponse response = watcher.waitForRpcStats(req.getTimeoutSec());
      synchronized (lock) {
        watchers.remove(watcher);
      }
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  /** Records the remote peer distribution for a given range of RPCs. */
  private static class XdsStatsWatcher {
    private final CountDownLatch latch;
    private final long startId;
    private final long endId;
    private final Map<String, Integer> rpcsByPeer = new HashMap<>();
    private final Map<String, Map<String, Integer>> rpcsByTypeAndPeer = new HashMap<>();
    private final Object lock = new Object();
    private int noRemotePeer;

    private XdsStatsWatcher(long startId, long endId) {
      latch = new CountDownLatch(Ints.checkedCast(endId - startId));
      this.startId = startId;
      this.endId = endId;
    }

    void rpcCompleted(String rpcType, long requestId, @Nullable String hostname) {
      synchronized (lock) {
        if (startId <= requestId && requestId < endId) {
          if (hostname != null) {
            if (rpcsByPeer.containsKey(hostname)) {
              rpcsByPeer.put(hostname, rpcsByPeer.get(hostname) + 1);
            } else {
              rpcsByPeer.put(hostname, 1);
            }
            if (rpcsByTypeAndPeer.containsKey(rpcType)) {
              if (rpcsByTypeAndPeer.get(rpcType).containsKey(hostname)) {
                rpcsByTypeAndPeer
                    .get(rpcType)
                    .put(hostname, rpcsByTypeAndPeer.get(rpcType).get(hostname) + 1);
              } else {
                rpcsByTypeAndPeer.get(rpcType).put(hostname, 1);
              }
            } else {
              Map<String, Integer> rpcMap = new HashMap<>();
              rpcMap.put(hostname, 1);
              rpcsByTypeAndPeer.put(rpcType, rpcMap);
            }
          } else {
            noRemotePeer += 1;
          }
          latch.countDown();
        }
      }
    }

    LoadBalancerStatsResponse waitForRpcStats(long timeoutSeconds) {
      try {
        boolean success = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!success) {
          logger.log(Level.INFO, "Await timed out, returning partial stats");
        }
      } catch (InterruptedException e) {
        logger.log(Level.INFO, "Await interrupted, returning partial stats", e);
        Thread.currentThread().interrupt();
      }
      LoadBalancerStatsResponse.Builder builder = LoadBalancerStatsResponse.newBuilder();
      synchronized (lock) {
        builder.putAllRpcsByPeer(rpcsByPeer);
        for (Map.Entry<String, Map<String, Integer>> entry : rpcsByTypeAndPeer.entrySet()) {
          LoadBalancerStatsResponse.RpcsByPeer.Builder rpcs =
              LoadBalancerStatsResponse.RpcsByPeer.newBuilder();
          rpcs.putAllRpcsByPeer(entry.getValue());
          builder.putRpcsByType(entry.getKey(), rpcs.build());
        }
        builder.setNumFailures(noRemotePeer + (int) latch.getCount());
      }
      return builder.build();
    }
  }
}
