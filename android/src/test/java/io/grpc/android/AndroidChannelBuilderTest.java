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
import android.telephony.TelephonyManager;

import org.junit.Before;
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
  private ConnectivityManager connectivityManager;
  private ShadowConnectivityManager shadowConnectivityManager;

  private final static NetworkInfo WIFI_CONNECTED = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
          ConnectivityManager.TYPE_WIFI, 0, true, true);
  private final static NetworkInfo WIFI_DISCONNECTED = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.DISCONNECTED,
          ConnectivityManager.TYPE_WIFI, 0, true, false);
  private final NetworkInfo MOBILE_CONNECTED = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
          ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_MOBILE_MMS, true, true);
  private final NetworkInfo MOBILE_DISCONNECTED = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.DISCONNECTED,
          ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_MOBILE_MMS, true, false);

  @Before
  public void setUp() {
    connectivityManager =
            (ConnectivityManager)
                    RuntimeEnvironment.application.getSystemService(Context.CONNECTIVITY_SERVICE);
    shadowConnectivityManager = shadowOf(connectivityManager);
  }

  @Test
  @Config(sdk = 16)
  public void resetConnectBackoff_api16() {
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel = new AndroidChannelBuilder.AndroidChannel(delegateChannel, RuntimeEnvironment.application.getApplicationContext());
    assertThat(delegateChannel.resetCount).isEqualTo(0);

    // On API levels < 24, the broadcast receiver will invoke resetConnectBackoff() on the first
    // connectivity action broadcast regardless of previous connection status.
    shadowConnectivityManager.setActiveNetworkInfo(WIFI_CONNECTED);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));


    androidChannel.shutdown();

    assertThat(delegateChannel.resetCount).isEqualTo(1);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0); // never called on
  }

  @Test
  @Config(sdk = 16)
  public void startDisconnected_newConnectionResetsConnectBackoff_api16() {
    shadowConnectivityManager.setActiveNetworkInfo(MOBILE_DISCONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel = new AndroidChannelBuilder.AndroidChannel(delegateChannel, RuntimeEnvironment.application.getApplicationContext());
    assertThat(delegateChannel.resetCount).isEqualTo(0);

    shadowConnectivityManager.setActiveNetworkInfo(MOBILE_CONNECTED);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    androidChannel.shutdown();

    assertThat(delegateChannel.resetCount).isEqualTo(1);
  }

  @Test
  @Config(sdk = 24)
  public void startConnected_newConnectionEntersIdle_api24() {
    shadowConnectivityManager.setActiveNetworkInfo(MOBILE_CONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel =  new AndroidChannelBuilder.AndroidChannel(delegateChannel, RuntimeEnvironment.application.getApplicationContext());

    assertThat(delegateChannel.resetCount).isEqualTo(0);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);

    shadowConnectivityManager.setActiveNetworkInfo(WIFI_CONNECTED);
    androidChannel.shutdown();

    assertThat(delegateChannel.resetCount).isEqualTo(0);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(1);

  }

  @Test
  @Config(sdk = 24)
  public void startDisconnected_newConnectionResetsConnectBackoff_api24() {
    shadowConnectivityManager.setActiveNetworkInfo(MOBILE_DISCONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel = new AndroidChannelBuilder.AndroidChannel(delegateChannel, RuntimeEnvironment.application.getApplicationContext());

    assertThat(delegateChannel.resetCount).isEqualTo(0);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);

    shadowConnectivityManager.setActiveNetworkInfo(MOBILE_CONNECTED);
    androidChannel.shutdown();

    assertThat(delegateChannel.resetCount).isEqualTo(1);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);
  }

  @Test
  @Config(sdk = 16)
  public void channelShutdownUnregistersCallbacks_api16() {
    shadowConnectivityManager.setActiveNetworkInfo(WIFI_DISCONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel = new AndroidChannelBuilder.AndroidChannel(delegateChannel, RuntimeEnvironment.application.getApplicationContext());

    androidChannel.shutdown();

    shadowConnectivityManager.setActiveNetworkInfo(WIFI_CONNECTED);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertThat(delegateChannel.resetCount).isEqualTo(0);
  }

  @Test
  @Config(sdk = 24)
  public void channelShutdownUnregistersCallbacks_api24() {
    shadowConnectivityManager.setActiveNetworkInfo(WIFI_DISCONNECTED);
    TestChannel delegateChannel = new TestChannel();
    ManagedChannel androidChannel =  new AndroidChannelBuilder.AndroidChannel(delegateChannel, RuntimeEnvironment.application.getApplicationContext());

    androidChannel.shutdown();

    shadowConnectivityManager.setActiveNetworkInfo(WIFI_CONNECTED);

    assertThat(delegateChannel.resetCount).isEqualTo(0);
    assertThat(delegateChannel.enterIdleCount).isEqualTo(0);
  }

  @Implements(value = ConnectivityManager.class)
  public static class ShadowDefaultNetworkListenerConnectivityManager extends ShadowConnectivityManager {
    private HashSet<ConnectivityManager.NetworkCallback> defaultNetworkCallbacks = new HashSet<>();

    public ShadowDefaultNetworkListenerConnectivityManager() {
      super();
    }

    @Override
    public void setActiveNetworkInfo(NetworkInfo activeNetworkInfo) {
      if (getApiLevel() >= N) {
        NetworkInfo previousNetworkInfo = getActiveNetworkInfo();
        if (activeNetworkInfo != previousNetworkInfo) {
          if (activeNetworkInfo != null) {
            notifyDefaultNetworkCallbacksOnAvailable(ShadowNetwork.newInstance(activeNetworkInfo.getType() /* use type as network ID */));
          } else {
            notifyDefaultNetworkCallbacksOnLost(ShadowNetwork.newInstance(previousNetworkInfo.getType() /* use type as network ID */));
          }
        }
      }
      super.setActiveNetworkInfo(activeNetworkInfo);
    }

    private void notifyDefaultNetworkCallbacksOnAvailable(Network network) {
      for (ConnectivityManager.NetworkCallback networkCallback : defaultNetworkCallbacks) {
        networkCallback.onAvailable(network);
      }
    }

    private void notifyDefaultNetworkCallbacksOnLost(Network network) {
      for (ConnectivityManager.NetworkCallback networkCallback : defaultNetworkCallbacks) {
        networkCallback.onLost(network);
      }
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

  private static class TestChannel extends ManagedChannel {
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