/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc.helloworldexample;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.okhttp.OkHttpChannelBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

public class HelloworldActivity extends AppCompatActivity {
  private Button sendButton;
  private EditText hostEdit;
  private EditText portEdit;
  private EditText messageEdit;
  private TextView resultText;

  private ManagedChannel channel;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_helloworld);
    sendButton = (Button) findViewById(R.id.send_button);
    hostEdit = (EditText) findViewById(R.id.host_edit_text);
    portEdit = (EditText) findViewById(R.id.port_edit_text);
    messageEdit = (EditText) findViewById(R.id.message_edit_text);
    resultText = (TextView) findViewById(R.id.grpc_response_text);
    resultText.setMovementMethod(new ScrollingMovementMethod());

    hostEdit.setText("grpc-test.sandbox.googleapis.com");
    portEdit.setText("443");

//    String loggingConfig =
//            "handlers=java.util.logging.ConsoleHandler\n"
//                    + "io.grpc.level=FINE\n"
//                    + "java.util.logging.ConsoleHandler.level=FINE\n"
//                    + "java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter";
//    try {
//      java.util.logging.LogManager.getLogManager()
//              .readConfiguration(
//                      new java.io.ByteArrayInputStream(
//                              loggingConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
//    } catch (IOException e) {
//      e.printStackTrace();
//    }
  }

  public void sendMessage(View view) {
    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(hostEdit.getWindowToken(), 0);
    sendButton.setEnabled(false);
    resultText.setText("");
    if (channel == null) {
      String host = hostEdit.getText().toString();
      String portStr = portEdit.getText().toString();
      int port = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
      channel = new AndroidChannel(
              OkHttpChannelBuilder
                      .forTarget("dns:///" + host + ":" + port)
                      .build());
    }
    new GrpcTask(this, channel)
        .execute(messageEdit.getText().toString());
  }

  public void prepareToLoseNetwork(View view) {
    if (channel != null) {
      Log.e("grpcHelloworld", "Invoking prepareToLoseNetwork");
      channel.prepareToLoseNetwork();
    }
  }

  public void resetConnectBackoff(View view) {
    if (channel != null) {
      Log.e("grpcHelloworld", "Invoking resetConnectBackoff");
      channel.resetConnectBackoff();
    }
  }

  public void shutdownChannel(View view) {
    if (channel != null) {
      channel.shutdown();
      channel = null;
    }
  }

  private static class GrpcTask extends AsyncTask<String, Void, String> {
    private final WeakReference<Activity> activityReference;
    private final ManagedChannel channel;

    private GrpcTask(Activity activity, ManagedChannel channel) {
      this.activityReference = new WeakReference<Activity>(activity);
      this.channel = channel;
    }

    @Override
    protected String doInBackground(String... params) {
      String message = params[0];
      try {
        GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
        HelloRequest request = HelloRequest.newBuilder().setName(message).build();
        HelloReply reply = stub.sayHello(request);
        return reply.getMessage();
      } catch (Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
        return String.format("Failed... : %n%s", sw);
      }
    }

    @Override
    protected void onPostExecute(String result) {
      Activity activity = activityReference.get();
      if (activity == null) {
        return;
      }
      TextView resultText = (TextView) activity.findViewById(R.id.grpc_response_text);
      Button sendButton = (Button) activity.findViewById(R.id.send_button);
      resultText.setText(result);
      sendButton.setEnabled(true);
    }
  }

  private class AndroidChannel extends ManagedChannel {
    private final ManagedChannel delegate;
    private final NetworkReceiver networkReceiver;
    private final IntentFilter networkIntentFilter;
    private final ConnectivityManager conn = (ConnectivityManager)
            getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    private NNetworkCallback nNetworkCallback;

    private final boolean useVersionNNetworkApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;

    @RequiresApi(api = Build.VERSION_CODES.N)
    private class NNetworkCallback {
      private final NamedCallback defaultNetworkCallback = new NamedCallback("defaultNetworkCallback");

      private void register(ConnectivityManager conn) {
        Log.e("grpcHelloworld", "Registering network callback");
        conn.registerDefaultNetworkCallback(defaultNetworkCallback);
      }

      private void unregister(ConnectivityManager conn) {
        Log.e("grpcHelloworld", "Unregistering network callback");
        conn.unregisterNetworkCallback(defaultNetworkCallback);
      }

      private class NamedCallback extends ConnectivityManager.NetworkCallback {

        final String name;
        private boolean isConnected;

        private NamedCallback(String name) {
          this.name = name;
        }

        @Override
        public void onAvailable(Network network) {
          Log.e("grpcHelloworld", name + ": onAvailable: " + network);
          Log.e("grpcHelloworld", "wasConnected: " + isConnected);
          if (isConnected) {
            Log.e("grpcHelloworld", "Invoking prepareToLoseNetwork");
            delegate.prepareToLoseNetwork();
          } else {
            Log.e("grpcHelloworld", "Invoking resetConnectBackoff");
            delegate.resetConnectBackoff();
          }
          isConnected = true;
        }

        @Override
        public void onLost(Network network) {
          isConnected = false;
          Log.e("grpcHelloworld", name + ": onLost: " + network);
        }
      }
    }

    // TODO: pass in Context
    @SuppressLint("NewApi")
    AndroidChannel(final ManagedChannel delegate) {
      this.delegate = delegate;
      networkReceiver = new NetworkReceiver();
      networkIntentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);

      if (useVersionNNetworkApi) {
        nNetworkCallback = new NNetworkCallback();
        nNetworkCallback.register(conn);
      } else {
        Log.e("grpcHelloworld", "Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT);
        getApplicationContext().registerReceiver(networkReceiver, networkIntentFilter);
      }
    }

    @SuppressLint("NewApi")
    @Override
    public ManagedChannel shutdown() {
      if (useVersionNNetworkApi) {
        nNetworkCallback.unregister(conn);
      } else {
        // Throws if not registered :/
        getApplicationContext().unregisterReceiver(networkReceiver);
      }
      return delegate.shutdown();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public ManagedChannel shutdownNow() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        nNetworkCallback.unregister(conn);
      } else {
        // Throws if not registered :/
        getApplicationContext().unregisterReceiver(networkReceiver);
      }
      return delegate.shutdownNow();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
            MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
      return delegate.newCall(methodDescriptor, callOptions);
    }

    @Override
    public String authority() {
      return delegate.authority();
    }

    @Override
    public ConnectivityState getState(boolean requestConnection) {
      return delegate.getState(requestConnection);
    }

    @Override
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
      delegate.notifyWhenStateChanged(source, callback);
    }

    @Override
    public void resetConnectBackoff() {
      delegate.resetConnectBackoff();
    }

    @Override
    public void prepareToLoseNetwork() {
      delegate.prepareToLoseNetwork();
    }

    private class NetworkReceiver extends BroadcastReceiver {
      private boolean wasConnected = true;

      @Override
      public void onReceive(Context context, Intent intent) {
        ConnectivityManager conn =  (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = conn.getActiveNetworkInfo(); // TODO: document required permission
        Log.e("grpcHelloworld", "onReceive: networkInfo: " + networkInfo);
        Log.e("grpcHelloworld", "wasConnected: " + wasConnected);
        // networkInfo may go to null first even when switching from wifi to cell
        boolean connected = networkInfo != null && networkInfo.isConnected();
        if (connected && !wasConnected) {
          Log.e("grpcHelloworld", "Invoking reset connect backoff");
          delegate.resetConnectBackoff();
        }
        wasConnected = connected;
      }
    }
  }
}
