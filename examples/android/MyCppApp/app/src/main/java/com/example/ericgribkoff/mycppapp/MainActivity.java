package com.example.ericgribkoff.mycppapp;

import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    String cert = "";

    class RetrieveFileTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... strings) {
            System.out.println("doInBackground");
            try {
                //set the download URL, a url that points to a file on the internet
                //this is the file to be downloaded
                URL url = new URL("https://raw.githubusercontent.com/grpc/grpc/master/etc/roots.pem");

                //create the new connection
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

                //set up some things on the connection
                urlConnection.setRequestMethod("GET");

                //and connect!
                urlConnection.connect();

                File file = new File(MainActivity.this.getFilesDir(), "certs.pem");


                //this will be used to write the downloaded data into the file we created
                FileOutputStream fileOutput = new FileOutputStream(file);

                //this will be used in reading the data from the internet
                InputStream inputStream = urlConnection.getInputStream();

                //this is the total size of the file
                int totalSize = urlConnection.getContentLength();
                //variable to store total downloaded bytes
                int downloadedSize = 0;

                //create a buffer...
                byte[] buffer = new byte[1024];
                int bufferLength = 0; //used to store a temporary size of the buffer

                //now, read through the input buffer and write the contents to the file
                while ( (bufferLength = inputStream.read(buffer)) > 0 ) {
                    //add the data in the buffer to the file in the file output stream (the file on the sd card
                    fileOutput.write(buffer, 0, bufferLength);
                    //add up the size so we know how much is downloaded
                    downloadedSize += bufferLength;
                }
                //close the output stream when done
                fileOutput.close();
                System.out.println("file: " + file.getAbsolutePath());
                return file.getAbsolutePath();

//catch some possible errors...
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Error!");
            return "error";
        }

        @Override
        protected void onPostExecute(String string) {
            System.out.println("onPostExecute");
            System.out.println("path to file: " + string);
            cert = string;
        }
    }


    class RunServerTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            startServer();
            return null;
        }

        @Override
        protected void onCancelled(Void result) {
            stopServer();
        }
    }

    private RunServerTask runServerTask;

    public boolean isRunServerTaskCancelled() {
        if (runServerTask != null) {
            return runServerTask.isCancelled();
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (runServerTask != null) {
            runServerTask.cancel(true);
            runServerTask = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        runServerTask = new RunServerTask();
        runServerTask.execute();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

//        new RetrieveFileTask().execute();


        Button stopServerButton = (Button) findViewById(R.id.stop_server);
        stopServerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AssetManager assetManager = getAssets();
                FileOutputStream fileOutput = null;
                InputStream inputStream = null;
                File file = new File(MainActivity.this.getFilesDir(), "certs.pem");
                try {
                    fileOutput = new FileOutputStream(file);
                    inputStream = assetManager.open("roots.pem");

                    byte[] buffer = new byte[1024];
                    int bufferLength;
                    while ( (bufferLength = inputStream.read(buffer)) > 0 ) {
                        fileOutput.write(buffer, 0, bufferLength);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (fileOutput != null) {
                        try {
                            fileOutput.close();
                        } catch (IOException e) {
                        }
                    }
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }


                // Example of a call to a native method
                TextView tv = (TextView) findViewById(R.id.sample_text);
                tv.setText(stringFromJNI(file.getAbsolutePath()));
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {


        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI(String cert);


    public native void startServer();

    public native void stopServer();
}
