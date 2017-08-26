/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.grpclb.LoadBalanceResponse.LoadBalanceResponseTypeCase;
import io.grpc.internal.LogId;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * The states of a GRPCLB working session of {@link GrpclbLoadBalancer}.  Created when
 * GrpclbLoadBalancer switches to GRPCLB mode.  Closed and discarded when GrpclbLoadBalancer
 * switches away from GRPCLB mode.
 */
// TODO(zhangkun83): round-robin on the backend list from the resolver if we don't get a server list
// within a configurable timeout.
@NotThreadSafe
final class GrpclbState {
  private static final Logger logger = Logger.getLogger(GrpclbState.class.getName());

  @VisibleForTesting
  static final PickResult DROP_PICK_RESULT =
      PickResult.withError(Status.UNAVAILABLE.withDescription("Dropped as requested by balancer"));

  @VisibleForTesting
  static final RoundRobinEntry BUFFER_ENTRY = new RoundRobinEntry() {
      @Override
      public PickResult picked(Metadata headers) {
        return PickResult.withNoResult();
      }
    };

  private final LogId logId;
  private final String serviceName;
  private final Helper helper;
  private final TimeProvider time;
  private final ScheduledExecutorService timerService;

  private static final Attributes.Key<AtomicReference<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.of("io.grpc.grpclb.GrpclbLoadBalancer.stateInfo");

  @Nullable
  private ManagedChannel lbCommChannel;

  @Nullable
  private LbStream lbStream;
  private Map<EquivalentAddressGroup, Subchannel> subchannels = Collections.emptyMap();

  // Has the same size as the round-robin list from the balancer.
  // A drop entry from the round-robin list becomes a DropEntry here.
  // A backend entry from the robin-robin list becomes a null here.
  private List<DropEntry> dropList = Collections.emptyList();
  // Contains only non-drop, i.e., backends from the round-robin list from the balancer.
  private List<BackendEntry> backendList = Collections.emptyList();
  private RoundRobinPicker currentPicker =
      new RoundRobinPicker(Collections.<DropEntry>emptyList(), Arrays.asList(BUFFER_ENTRY));

  GrpclbState(
      Helper helper, TimeProvider time, ScheduledExecutorService timerService, LogId logId) {
    this.helper = checkNotNull(helper, "helper");
    this.time = checkNotNull(time, "time provider");
    this.timerService = checkNotNull(timerService, "timerService");
    this.serviceName = checkNotNull(helper.getAuthority(), "helper returns null authority");
    this.logId = checkNotNull(logId, "logId");
  }

  void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    if (newState.getState() == SHUTDOWN || !(subchannels.values().contains(subchannel))) {
      return;
    }
    if (newState.getState() == IDLE) {
      subchannel.requestConnection();
    }
    subchannel.getAttributes().get(STATE_INFO).set(newState);
    maybeUpdatePicker();
  }

  /**
   * Set the address of the balancer, and create connection if not yet connected.
   */
  void setLbAddress(LbAddressGroup newLbAddressGroup) {
    startLbComm(newLbAddressGroup);
    // Avoid creating a new RPC just because the addresses were updated, as it can cause a
    // stampeding herd. The current RPC may be on a connection to an address not present in
    // newLbAddressGroups, but we're considering that "okay". If we detected the RPC is to an
    // outdated backend, we could choose to re-create the RPC.
    if (lbStream == null) {
      startLbRpc();
    }
  }

  private void shutdownLbComm() {
    if (lbCommChannel != null) {
      lbCommChannel.shutdown();
      lbCommChannel = null;
    }
    shutdownLbRpc();
  }

  private void shutdownLbRpc() {
    if (lbStream != null) {
      lbStream.close(null);
      // lbStream will be set to null in LbStream.cleanup()
    }
  }

  private void startLbComm(LbAddressGroup lbAddressGroup) {
    checkNotNull(lbAddressGroup, "lbAddressGroup");
    if (lbCommChannel == null) {
      lbCommChannel = helper.createOobChannel(
          lbAddressGroup.getAddresses(), lbAddressGroup.getAuthority());
    } else if (lbAddressGroup.getAuthority().equals(lbCommChannel.authority())) {
      helper.updateOobChannelAddresses(lbCommChannel, lbAddressGroup.getAddresses());
    } else {
      // Full restart of channel
      shutdownLbComm();
      lbCommChannel = helper.createOobChannel(
          lbAddressGroup.getAddresses(), lbAddressGroup.getAuthority());
    }
  }

  private void startLbRpc() {
    checkState(lbStream == null, "previous lbStream has not been cleared yet");
    LoadBalancerGrpc.LoadBalancerStub stub = LoadBalancerGrpc.newStub(lbCommChannel);
    lbStream = new LbStream(stub);

    LoadBalanceRequest initRequest = LoadBalanceRequest.newBuilder()
        .setInitialRequest(InitialLoadBalanceRequest.newBuilder()
            .setName(serviceName).build())
        .build();
    try {
      lbStream.lbRequestWriter.onNext(initRequest);
    } catch (Exception e) {
      lbStream.close(e);
    }
  }

  void shutdown() {
    shutdownLbComm();
    for (Subchannel subchannel : subchannels.values()) {
      subchannel.shutdown();
    }
    subchannels = Collections.emptyMap();
  }

  void propagateError(Status status) {
    logger.log(Level.FINE, "[{0}] Had an error: {1}; dropList={2}; backendList={3}",
        new Object[] {logId, status, dropList, backendList});
    if (backendList.isEmpty()) {
      maybeUpdatePicker(
          TRANSIENT_FAILURE, new RoundRobinPicker(dropList, Arrays.asList(new ErrorEntry(status))));
    }
  }

  @VisibleForTesting
  @Nullable
  GrpclbClientLoadRecorder getLoadRecorder() {
    if (lbStream == null) {
      return null;
    }
    return lbStream.loadRecorder;
  }

  private class LbStream implements StreamObserver<LoadBalanceResponse> {
    final StreamObserver<LoadBalanceRequest> lbRequestWriter;
    final GrpclbClientLoadRecorder loadRecorder;

    final Runnable loadReportRunnable = new Runnable() {
        @Override
        public void run() {
          helper.runSerialized(new Runnable() {
              @Override
              public void run() {
                loadReportTask = null;
                sendLoadReport();
              }
            });
        }
      };

    // These fields are only accessed from helper.runSerialized()
    boolean initialResponseReceived;
    boolean closed;
    long loadReportIntervalMillis = -1;
    ScheduledFuture<?> loadReportTask;

    LbStream(LoadBalancerGrpc.LoadBalancerStub stub) {
      // Stats data only valid for current LbStream.  We do not carry over data from previous
      // stream.
      loadRecorder = new GrpclbClientLoadRecorder(time);
      lbRequestWriter = stub.withWaitForReady().balanceLoad(this);
    }

    @Override public void onNext(final LoadBalanceResponse response) {
      helper.runSerialized(new Runnable() {
          @Override
          public void run() {
            handleResponse(response);
          }
        });
    }

    @Override public void onError(final Throwable error) {
      helper.runSerialized(new Runnable() {
          @Override
          public void run() {
            handleStreamClosed(Status.fromThrowable(error)
                .augmentDescription("Stream to GRPCLB LoadBalancer had an error"));
          }
        });
    }

    @Override public void onCompleted() {
      helper.runSerialized(new Runnable() {
          @Override
          public void run() {
            handleStreamClosed(
                Status.UNAVAILABLE.withDescription("Stream to GRPCLB LoadBalancer was closed"));
          }
        });
    }

    // Following methods must be run in helper.runSerialized()

    private void sendLoadReport() {
      if (closed) {
        return;
      }
      ClientStats stats = loadRecorder.generateLoadReport();
      // TODO(zhangkun83): flow control?
      try {
        lbRequestWriter.onNext(LoadBalanceRequest.newBuilder().setClientStats(stats).build());
        scheduleNextLoadReport();
      } catch (Exception e) {
        close(e);
      }
    }

    private void scheduleNextLoadReport() {
      if (loadReportIntervalMillis > 0) {
        loadReportTask = timerService.schedule(
            loadReportRunnable, loadReportIntervalMillis, TimeUnit.MILLISECONDS);
      }
    }

    private void handleResponse(LoadBalanceResponse response) {
      if (closed) {
        return;
      }
      logger.log(Level.FINE, "[{0}] Got an LB response: {1}", new Object[] {logId, response});

      LoadBalanceResponseTypeCase typeCase = response.getLoadBalanceResponseTypeCase();
      if (!initialResponseReceived) {
        if (typeCase != LoadBalanceResponseTypeCase.INITIAL_RESPONSE) {
          logger.log(
              Level.WARNING,
              "[{0}] : Did not receive response with type initial response: {1}",
              new Object[] {logId, response});
          return;
        }
        initialResponseReceived = true;
        InitialLoadBalanceResponse initialResponse = response.getInitialResponse();
        loadReportIntervalMillis =
            Durations.toMillis(initialResponse.getClientStatsReportInterval());
        scheduleNextLoadReport();
        return;
      }

      if (typeCase != LoadBalanceResponseTypeCase.SERVER_LIST) {
        logger.log(
            Level.WARNING,
            "[{0}] : Ignoring unexpected response type: {1}",
            new Object[] {logId, response});
        return;
      }

      // TODO(zhangkun83): handle delegate from initialResponse
      ServerList serverList = response.getServerList();
      HashMap<EquivalentAddressGroup, Subchannel> newSubchannelMap =
          new HashMap<EquivalentAddressGroup, Subchannel>();
      List<DropEntry> newDropList = new ArrayList<DropEntry>();
      List<BackendEntry> newBackendList = new ArrayList<BackendEntry>();
      // Construct the new collections. Create new Subchannels when necessary.
      for (Server server : serverList.getServersList()) {
        String token = server.getLoadBalanceToken();
        if (server.getDrop()) {
          newDropList.add(new DropEntry(loadRecorder, token));
        } else {
          newDropList.add(null);
          InetSocketAddress address;
          try {
            address = new InetSocketAddress(
                InetAddress.getByAddress(server.getIpAddress().toByteArray()), server.getPort());
          } catch (UnknownHostException e) {
            propagateError(Status.UNAVAILABLE.withCause(e));
            continue;
          }
          EquivalentAddressGroup eag = new EquivalentAddressGroup(address);
          Subchannel subchannel = newSubchannelMap.get(eag);
          if (subchannel == null) {
            subchannel = subchannels.get(eag);
            if (subchannel == null) {
              Attributes subchannelAttrs = Attributes.newBuilder()
                  .set(STATE_INFO,
                      new AtomicReference<ConnectivityStateInfo>(
                          ConnectivityStateInfo.forNonError(IDLE)))
                  .build();
              subchannel = helper.createSubchannel(eag, subchannelAttrs);
              subchannel.requestConnection();
            }
            newSubchannelMap.put(eag, subchannel);
          }
          newBackendList.add(new BackendEntry(subchannel, loadRecorder, token));
        }
      }
      // Close Subchannels whose addresses have been delisted
      for (Entry<EquivalentAddressGroup, Subchannel> entry : subchannels.entrySet()) {
        EquivalentAddressGroup eag = entry.getKey();
        if (!newSubchannelMap.containsKey(eag)) {
          entry.getValue().shutdown();
        }
      }

      subchannels = Collections.unmodifiableMap(newSubchannelMap);
      dropList = Collections.unmodifiableList(newDropList);
      backendList = Collections.unmodifiableList(newBackendList);
      maybeUpdatePicker();
    }

    private void handleStreamClosed(Status status) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      propagateError(status);
      startLbRpc();
    }

    void close(@Nullable Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      try {
        if (error == null) {
          lbRequestWriter.onCompleted();
        } else {
          lbRequestWriter.onError(error);
        }
      } catch (Exception e) {
        // Don't care
      }
    }

    private void cleanUp() {
      if (loadReportTask != null) {
        loadReportTask.cancel(false);
        loadReportTask = null;
      }
      if (lbStream == this) {
        lbStream = null;
      }
    }
  }

  /**
   * Make and use a picker out of the current lists and the states of subchannels if they have
   * changed since the last picker created.
   */
  private void maybeUpdatePicker() {
    List<RoundRobinEntry> pickList = new ArrayList<RoundRobinEntry>(backendList.size());
    Status error = null;
    boolean hasIdle = false;
    for (BackendEntry entry : backendList) {
      Subchannel subchannel = entry.result.getSubchannel();
      Attributes attrs = subchannel.getAttributes();
      ConnectivityStateInfo stateInfo = attrs.get(STATE_INFO).get();
      if (stateInfo.getState() == READY) {
        pickList.add(entry);
      } else if (stateInfo.getState() == TRANSIENT_FAILURE) {
        error = stateInfo.getStatus();
      } else if (stateInfo.getState() == IDLE) {
        hasIdle = true;
      }
    }
    ConnectivityState state;
    if (pickList.isEmpty()) {
      if (error != null && !hasIdle) {
        logger.log(Level.FINE, "[{0}] No ready Subchannel. Using error: {1}",
            new Object[] {logId, error});
        pickList.add(new ErrorEntry(error));
        state = TRANSIENT_FAILURE;
      } else {
        logger.log(Level.FINE, "[{0}] No ready Subchannel and still connecting", logId);
        pickList.add(BUFFER_ENTRY);
        state = CONNECTING;
      }
    } else {
      logger.log(
          Level.FINE, "[{0}] Using drop list {1} and pick list {2}",
          new Object[] {logId, dropList, pickList});
      state = READY;
    }
    maybeUpdatePicker(state, new RoundRobinPicker(dropList, pickList));
  }

  /**
   * Update the given picker to the helper if it's different from the current one.
   */
  private void maybeUpdatePicker(ConnectivityState state, RoundRobinPicker picker) {
    // Discard the new picker if we are sure it won't make any difference, in order to save
    // re-processing pending streams, and avoid unnecessary resetting of the pointer in
    // RoundRobinPicker.
    if (picker.dropList.equals(currentPicker.dropList)
        && picker.pickList.equals(currentPicker.pickList)) {
      return;
    }
    // No need to skip ErrorPicker. If the current picker is ErrorPicker, there won't be any pending
    // stream thus no time is wasted in re-process.
    currentPicker = picker;
    helper.updateBalancingState(state, picker);
  }

  @VisibleForTesting
  static final class DropEntry {
    private final GrpclbClientLoadRecorder loadRecorder;
    private final String token;

    DropEntry(GrpclbClientLoadRecorder loadRecorder, String token) {
      this.loadRecorder = checkNotNull(loadRecorder, "loadRecorder");
      this.token = checkNotNull(token, "token");
    }

    PickResult picked() {
      loadRecorder.recordDroppedRequest(token);
      return DROP_PICK_RESULT;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("loadRecorder", loadRecorder)
          .add("token", token)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(loadRecorder, token);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof DropEntry)) {
        return false;
      }
      DropEntry that = (DropEntry) other;
      return Objects.equal(loadRecorder, that.loadRecorder) && Objects.equal(token, that.token);
    }
  }

  private interface RoundRobinEntry {
    PickResult picked(Metadata headers);
  }

  @VisibleForTesting
  static final class BackendEntry implements RoundRobinEntry {
    @VisibleForTesting
    final PickResult result;
    private final GrpclbClientLoadRecorder loadRecorder;
    private final String token;

    BackendEntry(Subchannel subchannel, GrpclbClientLoadRecorder loadRecorder, String token) {
      this.result = PickResult.withSubchannel(subchannel, loadRecorder);
      this.loadRecorder = checkNotNull(loadRecorder, "loadRecorder");
      this.token = checkNotNull(token, "token");
    }

    @Override
    public PickResult picked(Metadata headers) {
      headers.discardAll(GrpclbConstants.TOKEN_METADATA_KEY);
      headers.put(GrpclbConstants.TOKEN_METADATA_KEY, token);
      return result;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("result", result)
          .add("loadRecorder", loadRecorder)
          .add("token", token)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(loadRecorder, result, token);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof BackendEntry)) {
        return false;
      }
      BackendEntry that = (BackendEntry) other;
      return Objects.equal(result, that.result) && Objects.equal(token, that.token)
          && Objects.equal(loadRecorder, that.loadRecorder);
    }
  }

  @VisibleForTesting
  static final class ErrorEntry implements RoundRobinEntry {
    private final PickResult result;

    ErrorEntry(Status status) {
      result = PickResult.withError(status);
    }

    @Override
    public PickResult picked(Metadata headers) {
      return result;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(result);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ErrorEntry)) {
        return false;
      }
      return Objects.equal(result, ((ErrorEntry) other).result);
    }
  }

  @VisibleForTesting
  static final class RoundRobinPicker extends SubchannelPicker {
    @VisibleForTesting
    final List<DropEntry> dropList;
    private int dropIndex;

    @VisibleForTesting
    final List<? extends RoundRobinEntry> pickList;
    private int pickIndex;

    // dropList can be empty, which means no drop.
    // pickList must not be empty.
    RoundRobinPicker(List<DropEntry> dropList, List<? extends RoundRobinEntry> pickList) {
      this.dropList = checkNotNull(dropList, "dropList");
      this.pickList = checkNotNull(pickList, "pickList");
      checkArgument(!pickList.isEmpty(), "pickList is empty");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      synchronized (pickList) {
        // Two-level round-robin.
        // First round-robin on dropList. If a drop entry is selected, request will be dropped.  If
        // a non-drop entry is selected, then round-robin on pickList.  This makes sure requests are
        // dropped at the same proportion as the drop entries appear on the round-robin list from
        // the balancer, while only READY backends (that make up pickList) are selected for the
        // non-drop cases.
        if (!dropList.isEmpty()) {
          DropEntry drop = dropList.get(dropIndex);
          dropIndex++;
          if (dropIndex == dropList.size()) {
            dropIndex = 0;
          }
          if (drop != null) {
            return drop.picked();
          }
        }

        RoundRobinEntry pick = pickList.get(pickIndex);
        pickIndex++;
        if (pickIndex == pickList.size()) {
          pickIndex = 0;
        }
        return pick.picked(args.getHeaders());
      }
    }
  }
}
