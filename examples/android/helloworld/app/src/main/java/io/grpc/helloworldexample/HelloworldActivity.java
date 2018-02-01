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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

public class HelloworldActivity extends AppCompatActivity {
  private Button sendButton;
  private EditText hostEdit;
  private EditText portEdit;
  private EditText messageEdit;
  private TextView resultText;
  private ManagedChannel channel;

  // The BroadcastReceiver that tracks network connectivity changes.
  private NetworkReceiver receiver = new NetworkReceiver();

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

    // Registers BroadcastReceiver to track network connection changes.
    IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    receiver = new NetworkReceiver();
    this.registerReceiver(receiver, filter);

    String loggingConfig =
            "handlers=java.util.logging.ConsoleHandler\n"
                    + "io.grpc.level=FINE\n"
                    + "java.util.logging.ConsoleHandler.level=FINE\n"
                    + "java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter";
    try {
      java.util.logging.LogManager.getLogManager()
              .readConfiguration(
                      new java.io.ByteArrayInputStream(
                              loggingConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public void sendMessage(View view) {
    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(hostEdit.getWindowToken(), 0);
    sendButton.setEnabled(false);
    resultText.setText("");
    if (channel == null) {
//      String portStr = portEdit.getText().toString();
//      int port = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
      channel = ManagedChannelBuilder.forAddress("grpc-test.sandbox.googleapis.com", 443).idleTimeout(10, TimeUnit.SECONDS).build();
    }
    new GrpcTask(this, channel)
        .execute(
            hostEdit.getText().toString(),
            messageEdit.getText().toString(),
            portEdit.getText().toString());
  }

  private static class GrpcTask extends AsyncTask<String, Void, String> {
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;

    private GrpcTask(Activity activity, ManagedChannel channel) {
      this.activityReference = new WeakReference<Activity>(activity);
      this.channel = channel;
    }

    @Override
    protected String doInBackground(String... params) {
      String host = params[0];
      String message = params[1];
      String portStr = params[2];
      int port = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
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
//      try {
//        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
//      } catch (InterruptedException e) {
//        Thread.currentThread().interrupt();
//      }
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

  public class NetworkReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      ConnectivityManager conn = (ConnectivityManager)
              context.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo networkInfo = conn.getActiveNetworkInfo();

      if (networkInfo == null) {
        System.out.println("active network: null");
        return;
      }
      System.out.println("active network: " + networkInfo.getTypeName());
      System.out.println("isConnected: " + networkInfo.isConnected());

      if (channel != null) {
        System.out.println("invoking reset connect backoff");
        channel.resetConnectBackoff();
      }

    }
  }
}
