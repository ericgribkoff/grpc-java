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

import android.content.Context;
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
import io.grpc.okhttp.NegotiationType;
import io.grpc.okhttp.OkHttpChannelBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

public class HelloworldActivity extends AppCompatActivity {
    private Button mSendButton;
    private EditText mHostEdit;
    private EditText mPortEdit;
    private EditText mMessageEdit;
    private TextView mResultText;
    private ManagedChannel mChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_helloworld);
        mSendButton = (Button) findViewById(R.id.send_button);
        mHostEdit = (EditText) findViewById(R.id.host_edit_text);
        mPortEdit = (EditText) findViewById(R.id.port_edit_text);
        mMessageEdit = (EditText) findViewById(R.id.message_edit_text);
        mResultText = (TextView) findViewById(R.id.grpc_response_text);
        mResultText.setMovementMethod(new ScrollingMovementMethod());

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
                .hideSoftInputFromWindow(mHostEdit.getWindowToken(), 0);
        mSendButton.setEnabled(false);
        if (mChannel == null) {
            mChannel = OkHttpChannelBuilder.forAddress(
//                    "grpc-test.sandbox.googleapis.com", 443)
                    "localhost", 8789)
                    .negotiationType(NegotiationType.PLAINTEXT)
//                    .usePlaintext(true)
                    .build();
        }
        new GrpcTask(mChannel).execute();
    }

    public void shutdownTransports(View view) {
        if (mChannel != null) {
            mChannel.shutdownTransports();
        }
    }

    private class GrpcTask extends AsyncTask<Void, Void, String> {
        private String mHost;
        private String mMessage;
        private int mPort;
        private ManagedChannel mChannel;

        private GrpcTask(ManagedChannel mChannel) {
            this.mChannel = mChannel;
        }

        @Override
        protected void onPreExecute() {
            mHost = mHostEdit.getText().toString();
            mMessage = mMessageEdit.getText().toString();
            String portStr = mPortEdit.getText().toString();
            mPort = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
            mResultText.setText("");
        }

        @Override
        protected String doInBackground(Void... nothing) {
            try {
                GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(mChannel);
                HelloRequest message = HelloRequest.newBuilder().setName(mMessage).build();
                HelloReply reply = stub.sayHello(message);
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
//            try {
//                mChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
            mResultText.setText(result);
            mSendButton.setEnabled(true);
        }
    }
}
