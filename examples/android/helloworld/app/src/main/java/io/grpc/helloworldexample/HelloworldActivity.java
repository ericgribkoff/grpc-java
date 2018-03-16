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
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.android.AndroidChannelBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.testing.integration.Messages;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.integration.TestServiceGrpc;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;

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
    initChannel();
    new GrpcTask(this, channel)
        .executeOnExecutor(THREAD_POOL_EXECUTOR, messageEdit.getText().toString());
  }

  private int streamCount = 0;

  public void initiateStream(View view) {
    initChannel();
    new GrpcStreamingTask(channel, streamCount++)
        .executeOnExecutor(THREAD_POOL_EXECUTOR);
  }

  public void resetChannel(View view) {
    if (channel != null) {
      channel.shutdown();
      channel = null;
    }
  }

  private void initChannel() {
    if (channel == null) {
      String host = hostEdit.getText().toString();
      String portStr = portEdit.getText().toString();
      int port = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
      channel =
          AndroidChannelBuilder.forAddress(host, port, getApplicationContext())
//              .usePlaintext()
              .build();
    }
  }

  private static class GrpcTask extends AsyncTask<String, Void, String> {
    private final WeakReference<Activity> activityReference;
    private final Channel channel;

    private GrpcTask(Activity activity, Channel channel) {
      this.activityReference = new WeakReference<Activity>(activity);
      this.channel = channel;
    }

    @Override
    protected String doInBackground(String... params) {
      String message = params[0];
      try {
        // TODO(ericgribkoff) Channel could be shutdown by this point
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

  private static class GrpcStreamingTask extends AsyncTask<Void, Void, Void> {
    private final Channel channel;
    private final int id;

    private GrpcStreamingTask(Channel channel, int id) {
      this.channel = channel;
      this.id = id;
    }

    @Override
    protected Void doInBackground(Void... params) {
      try {
        // TODO(ericgribkoff) Channel could be shutdown by this point
        TestServiceGrpc.TestServiceStub stub = TestServiceGrpc.newStub(channel);
        InfiniteStreamObserver responseObserver = new InfiniteStreamObserver(id);
        final StreamObserver<StreamingOutputCallRequest> requestObserver = stub.fullDuplexCall(responseObserver);
        responseObserver.requestObserver = requestObserver;
        requestObserver.onNext(getRequest());
        responseObserver.latch.await(1000, TimeUnit.SECONDS);
      } catch (Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
      }
      return null;
    }
  }

  private static StreamingOutputCallRequest getRequest() {
    return StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(Messages.ResponseParameters.newBuilder().setSize(31415))
        .build();
  }

  private static class InfiniteStreamObserver implements StreamObserver<StreamingOutputCallResponse> {
    private final int id;
    StreamObserver<StreamingOutputCallRequest> requestObserver;
    CountDownLatch latch = new CountDownLatch(1);

    private InfiniteStreamObserver(int id) {
      this.id = id;
    }

    @Override
    public void onNext(StreamingOutputCallResponse value) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      Log.d("grpc", "Sending request on infinite stream " + id);
      requestObserver.onNext(getRequest());
    }

    @Override
    public void onError(Throwable t) {
      Log.d("grpc", "Infinite stream " + id + " encountered an error", t);
      latch.countDown();
    }

    @Override
    public void onCompleted() {
      Log.d("grpc", "Infinite stream " + id + " is not so infinite");
      latch.countDown();
    }
  };
}
