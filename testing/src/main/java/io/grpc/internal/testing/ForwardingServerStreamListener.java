package io.grpc.internal.testing;

import io.grpc.Status;
import io.grpc.internal.MessageDeframer;
import io.grpc.internal.ServerStreamListener;

import java.io.InputStream;

/**
 * A ClientStreamListener implementation that handles calls to
 * {@link MessageDeframer.Sink.Listener#scheduleDeframerSource(MessageDeframer.Source)},
 * and otherwise delegates to a (typically mocked) wrapped ClientStreamObserver.
 */
public class ForwardingServerStreamListener implements ServerStreamListener {
  private final ServerStreamListener listener;

  public ForwardingServerStreamListener(ServerStreamListener listener) {
    this.listener = listener;
  }

  @Override
  public void halfClosed() {
    listener.halfClosed();
  }

  @Override
  public void closed(Status status) {
    listener.closed(status);
  }

  @Override
  public void messageRead(InputStream message) {
    listener.messageRead(message);
  }

  @Override
  public void scheduleDeframerSource(MessageDeframer.Source source) {
    InputStream message;
    while ((message = source.next()) != null) {
      listener.messageRead(message);
    }
  }

  @Override
  public void onReady() {
    listener.onReady();
  }
}
