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

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;

import io.grpc.ManagedChannel;

@RunWith(RobolectricTestRunner.class)
public final class AndroidChannelBuilderTest {
  @Test
  @Config(sdk = 16)
  public void returnedChannelInvokesResetConnectBackoffOnNetworkConnection_api16() {
    ConnectivityManager connectivityManager;
    ShadowNetworkInfo shadowOfActiveNetworkInfo;
    ShadowConnectivityManager shadowConnectivityManager;
    connectivityManager =
        (ConnectivityManager)
            RuntimeEnvironment.application.getSystemService(Context.CONNECTIVITY_SERVICE);
    shadowConnectivityManager = shadowOf(connectivityManager);
    shadowOfActiveNetworkInfo = shadowOf(connectivityManager.getActiveNetworkInfo());

    Context context = RuntimeEnvironment.application.getApplicationContext();

    shadowOfActiveNetworkInfo.setConnectionStatus(NetworkInfo.State.DISCONNECTED);

    ManagedChannel channel = AndroidChannelBuilder.forAddress("host", 123, context).build();

    shadowOfActiveNetworkInfo.setConnectionStatus(NetworkInfo.State.CONNECTED);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));

    assertTrue(true);
  }

  @Test
  @Config(sdk = 24)
  public void returnedChannelInvokesResetConnectBackoffOnNetworkConnection_api24() {
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

    ManagedChannel channel = AndroidChannelBuilder.forAddress("host", 123, context).build();

    shadowOfActiveNetworkInfo.setConnectionStatus(true);
    RuntimeEnvironment.application.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));

    assertTrue(true);
  }
}