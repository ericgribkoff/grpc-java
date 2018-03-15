/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.android;

import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.N;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertTrue;
import static org.robolectric.RuntimeEnvironment.getApiLevel;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.telephony.TelephonyManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;

@RunWith(RobolectricTestRunner.class)
@Config(shadows={AndroidChannelBuilderTest.ShadowDefaultNetworkListenerConnectivityManager.class})
public final class AndroidChannelBuilderTest {
  @Test
  @Config(sdk = 16)
  public void newConnectionResetsConnectBackoff_api16() {
    ConnectivityManager connectivityManager;
    ShadowNetworkInfo shadowOfActiveNetworkInfo;
    connectivityManager =
        (ConnectivityManager)
            RuntimeEnvironment.application.getSystemService(Context.CONNECTIVITY_SERVICE);
    shadowOfActiveNetworkInfo = shadowOf(connectivityManager.getActiveNetworkInfo());

    Context context = RuntimeEnvironment.application.getApplicationContext();

    shadowOfActiveNetworkInfo.setConnectionStatus(false);

    NetworkEventRecordingChannel recordingChannel = new NetworkEventRecordingChannel();
    ManagedChannel channel = new AndroidChannelBuilder.AndroidChannel(recordingChannel, context);
    assertThat(recordingChannel.resetCount).isEqualTo(0);

    shadowOfActiveNetworkInfo.setConnectionStatus(true);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(recordingChannel.resetCount).isEqualTo(1);

    assertTrue(true);
  }

  @Test
  @Config(sdk = 24)
  public void newConnectionResetsConnectBackoff_api24() {
    ConnectivityManager connectivityManager;
    ShadowNetworkInfo shadowOfActiveNetworkInfo;
    ShadowConnectivityManager shadowConnectivityManager;
    connectivityManager =
            (ConnectivityManager)
                    RuntimeEnvironment.application.getSystemService(Context.CONNECTIVITY_SERVICE);
    shadowConnectivityManager = shadowOf(connectivityManager);
    shadowOfActiveNetworkInfo = shadowOf(connectivityManager.getActiveNetworkInfo());
//
    Context context = RuntimeEnvironment.application.getApplicationContext();
//
    shadowConnectivityManager.setDefaultNetworkActive(true);
    shadowOfActiveNetworkInfo.setConnectionStatus(false);

    NetworkEventRecordingChannel recordingChannel = new NetworkEventRecordingChannel();
    ManagedChannel channel = new AndroidChannelBuilder.AndroidChannel(recordingChannel, context);
    assertThat(recordingChannel.resetCount).isEqualTo(0);

    NetworkInfo networkInfo = ShadowNetworkInfo.newInstance(
            null,
            ConnectivityManager.TYPE_MOBILE_HIPRI,
            TelephonyManager.NETWORK_TYPE_EDGE,
            true,
            false);
    shadowConnectivityManager.setActiveNetworkInfo(networkInfo);

    shadowOfActiveNetworkInfo.setConnectionStatus(true);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(recordingChannel.resetCount).isEqualTo(1);

    assertTrue(true);
  }

  @Implements(value = ConnectivityManager.class)
  public static class ShadowDefaultNetworkListenerConnectivityManager extends ShadowConnectivityManager {
    private HashSet<ConnectivityManager.NetworkCallback> defaultNetworkCallbacks = new HashSet<>();

    public ShadowDefaultNetworkListenerConnectivityManager() {
      super();
    }

    @Override
    public void setActiveNetworkInfo(NetworkInfo info) {
      if (getApiLevel() >= N) {
        if (info != getActiveNetworkInfo()) {
          // fire default network listeners
          System.out.println("changed default network");
          if (info != null) {
            for (ConnectivityManager.NetworkCallback networkCallback : defaultNetworkCallbacks) {
              Network network = ShadowNetwork.newInstance(info.getType() /* use type as psuedo network ID */);
              networkCallback.onAvailable(network);
            }
          }
        }
      }
      super.setActiveNetworkInfo(info);
    }

    @Implementation(minSdk = N)
    protected void registerDefaultNetworkCallback(ConnectivityManager.NetworkCallback networkCallback) {
      defaultNetworkCallbacks.add(networkCallback);
    }

    @Implementation(minSdk = LOLLIPOP)
    @Override
    public void unregisterNetworkCallback(ConnectivityManager.NetworkCallback networkCallback) {
      if (getApiLevel() >= N) {
        if (networkCallback != null && defaultNetworkCallbacks.contains(networkCallback)) {
          defaultNetworkCallbacks.remove(networkCallback);
        }
      }
      super.unregisterNetworkCallback(networkCallback);
    }
  }

  private static class NetworkEventRecordingChannel extends ManagedChannel {
    int resetCount;
    int enterIdleCount;

    @Override
    public ManagedChannel shutdown() {
      return null;
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return null;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return false;
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
      return null;
    }

    @Override
    public String authority() {
      return null;
    }

    @Override
    public void resetConnectBackoff() {
      resetCount++;
    }

    @Override
    public void enterIdle() {
      enterIdleCount++;
    }
  }
}