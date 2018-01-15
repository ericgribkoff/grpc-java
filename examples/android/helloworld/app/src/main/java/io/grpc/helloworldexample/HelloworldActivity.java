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
import android.content.DialogInterface;
import android.content.Intent;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.conscrypt.Conscrypt;
import java.security.Provider;
import java.security.Security;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.security.ProviderInstaller;

public class HelloworldActivity extends AppCompatActivity implements ProviderInstaller.ProviderInstallListener {
    private Button mSendButton;
    private EditText mHostEdit;
    private EditText mPortEdit;
    private EditText mMessageEdit;
    private TextView mResultText;

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

        for (Provider p : Security.getProviders()) {
            System.out.println(p + " " + p.getClass().getName());
        }

//        try {
//            ProviderInstaller.installIfNeeded(this);
//        } catch (GooglePlayServicesRepairableException e) {
//
//            // Indicates that Google Play services is out of date, disabled, etc.
//
//            // Prompt the user to install/update/enable Google Play services.
//            GooglePlayServicesUtil.showErrorNotification(
//                    e.getConnectionStatusCode(), this);
//            e.printStackTrace();
//            return;
//
//        } catch (GooglePlayServicesNotAvailableException e) {
//            // Indicates a non-recoverable error; the ProviderInstaller is not able
//            // to install an up-to-date Provider.
//
//            // Notify the SyncManager that a hard error occurred.
//
//            e.printStackTrace();
//            return;
//        }

        System.out.println(Security.addProvider(Conscrypt.newProvider()));
//        System.out.println(Security.insertProviderAt(Conscrypt.newProvider(), 1));
        for (Provider p : Security.getProviders()) {
            System.out.println(p + " " + p.getClass().getName());
        }

//        ProviderInstaller.installIfNeededAsync(this, this);

        String loggingConfig =
                "handlers=java.util.logging.ConsoleHandler\n"
                        + "io.grpc.level=FINE\n"
                        + "java.util.logging.ConsoleHandler.level=FINE\n"
                        + "java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter";
        try {
            java.util.logging.LogManager.getLogManager()
                    .readConfiguration(
                            new java.io.ByteArrayInputStream(
                                    loggingConfig.getBytes()));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void onProviderInstalled() {
        System.out.println("Success!");
    }

    @Override
    public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
        if (GooglePlayServicesUtil.isUserRecoverableError(errorCode)) {
            // Recoverable error. Show a dialog prompting the user to
            // install/update/enable Google Play services.
            GooglePlayServicesUtil.showErrorDialogFragment(
                    errorCode,
                    this,
                    1,
                    new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            // The user chose not to take the recovery action
                            System.out.println("failed1");
                        }
                    });
        } else {
            // Google Play services is not available.
            System.out.println("failed2");
        }
    }

    public void sendMessage(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(mHostEdit.getWindowToken(), 0);
        mSendButton.setEnabled(false);
        new GrpcTask().execute();
    }

    private class GrpcTask extends AsyncTask<Void, Void, String> {
        private String mHost;
        private String mMessage;
        private int mPort;
        private ManagedChannel mChannel;

        @Override
        protected void onPreExecute() {
            mHost = "grpc-test.sandbox.googleapis.com"; //mHostEdit.getText().toString();
            mMessage = "test"; //mMessageEdit.getText().toString();
//            String portStr = mPortEdit.getText().toString();
            mPort = 443; //TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
            mResultText.setText("");
        }

        @Override
        protected String doInBackground(Void... nothing) {
            try {
                mChannel = ManagedChannelBuilder.forAddress(mHost, mPort)
//                    .usePlaintext(true)
                    .build();
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
            try {
                mChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mResultText.setText(result);
            mSendButton.setEnabled(true);
        }
    }
}
