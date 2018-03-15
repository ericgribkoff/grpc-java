package io.grpc.android;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;

import com.google.common.annotations.VisibleForTesting;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.internal.GrpcUtil;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Builds a {@link ManagedChannel} that automatically monitors the Android device's network state.
 * Network changes are used to update the connectivity state of the underlying OkHttp-backed
 * {@ManagedChannel} to smoothly handle intermittent network failures.
 *
 * <p>gRPC Cronet users should use {@code CronetChannelBuilder} directly, as Cronet itself monitors
 * the device network state.
 *
 * <p>Requires the Android ACCESS_NETWORK_STATE permission.
 *
 * @since 1.12.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4056")
public final class AndroidChannelBuilder extends ForwardingChannelBuilder<AndroidChannelBuilder> {

  private final ManagedChannelBuilder delegateBuilder;
  private final Context context;

  /** Always fails. Call {@link #forAddress(String, int, Context)} instead. */
  public static AndroidChannelBuilder forTarget(String target) {
    throw new UnsupportedOperationException("call forTarget(String, Context) instead");
  }

  /** Always fails. Call {@link #forAddress(String, int, Context)} instead. */
  public static AndroidChannelBuilder forAddress(String name, int port) {
    throw new UnsupportedOperationException("call forAddress(String, int, Context) instead");
  }

  /** Creates a new builder for the given target and Android context. */
  public static final AndroidChannelBuilder forTarget(String target, Context context) {
    return new AndroidChannelBuilder(target, context);
  }

  /** Creates a new builder for the given host, port, and Android context. */
  public static AndroidChannelBuilder forAddress(String name, int port, Context context) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port), context);
  }

  private AndroidChannelBuilder(String target, Context context) {
    delegateBuilder = OkHttpChannelBuilder.forTarget(target);
    this.context = context;
  }

  @Override
  protected ManagedChannelBuilder<?> delegate() {
    return delegateBuilder;
  }

  @Override
  public ManagedChannel build() {
    return new AndroidChannel(delegateBuilder.build(), context);
  }

  /**
   * Wraps an OkHttp channel and handles invoking the appropriate methods (e.g., {@link
   * ManagedChannel#resetConnectBackoff}) when the device network state changes.
   */
  @VisibleForTesting
  static final class AndroidChannel extends ManagedChannel {

    private final ManagedChannel delegate;
    private final Context context;
    @Nullable private final ConnectivityManager connectivityManager;

    private DefaultNetworkCallback defaultNetworkCallback;
    private NetworkReceiver networkReceiver;
    private IntentFilter networkIntentFilter;

    @VisibleForTesting
    AndroidChannel(final ManagedChannel delegate, Context context) {
      this.delegate = delegate;
      this.context = context;
      connectivityManager =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

      System.out.println(Build.VERSION.SDK_INT);
      // Android N added the registerDefaultNetworkCallback API to listen to changes in the device's
      // default network. For earlier Android API levels, use the BroadcastReceiver API.
      if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        defaultNetworkCallback =
            new DefaultNetworkCallback(connectivityManager.getActiveNetworkInfo());
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        connectivityManager.registerNetworkCallback(builder.build(), defaultNetworkCallback);
        connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback);
      } else {
        networkReceiver = new NetworkReceiver();
        networkIntentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(networkReceiver, networkIntentFilter);
      }
    }

    @Override
    public ManagedChannel shutdown() {
      unregisterNetworkListener();
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
      unregisterNetworkListener();
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
    public void enterIdle() {
      delegate.enterIdle();
    }

    private void unregisterNetworkListener() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        connectivityManager.unregisterNetworkCallback(defaultNetworkCallback);
      } else {
        context.unregisterReceiver(networkReceiver);
      }
    }

    /** Respond to changes in the default network. Only used on API levels 24+. */
    @TargetApi(Build.VERSION_CODES.N)
    private class DefaultNetworkCallback extends ConnectivityManager.NetworkCallback {

      private boolean isConnected;

      DefaultNetworkCallback(NetworkInfo currentNetwork) {
        isConnected = currentNetwork != null && currentNetwork.isConnected();
      }

      @Override
      public void onAvailable(Network network) {
        if (isConnected) {
          delegate.enterIdle();
        } else {
          delegate.resetConnectBackoff();
        }
        isConnected = true;
      }

      @Override
      public void onLost(Network network) {
        isConnected = false;
      }
    }

    /** Respond to network changes. Only used on API levels < 24. */
    private class NetworkReceiver extends BroadcastReceiver {
      private boolean isConnected = false;

      @Override
      public void onReceive(Context context, Intent intent) {
        ConnectivityManager conn =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = conn.getActiveNetworkInfo();
        boolean connected = networkInfo != null && networkInfo.isConnected();
        if (!isConnected && connected) {
          delegate.resetConnectBackoff();
        }
        isConnected = connected;
      }
    }
  }
}
