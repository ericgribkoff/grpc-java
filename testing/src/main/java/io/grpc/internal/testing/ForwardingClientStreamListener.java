package io.grpc.internal.testing;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.MessageDeframer;

import java.io.InputStream;

/**
 * A ClientStreamListener implementations that handles calls to
 * {@link MessageDeframer.Sink.Listener#scheduleDeframerSource(MessageDeframer.Source)},
 * and otherwise delegates to a (typically mocked) wrapped ClientStreamObserver.
 */
public class ForwardingClientStreamListener implements ClientStreamListener {
  private final ClientStreamListener listener;

  public ForwardingClientStreamListener(ClientStreamListener listener) {
    this.listener = listener;
  }

  @Override
  public void headersRead(Metadata headers) {
    listener.headersRead(headers);
  }

  @Override
  public void closed(Status status, Metadata trailers) {
    listener.closed(status, trailers);
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
