package io.grpc.internal;

import java.io.Closeable;

/**
 * Created by ericgribkoff on 8/17/17.
 */
public interface CompositeBuffer extends Closeable {
  void addBuffer(ReadableBuffer buffer);

  int readableBytes();

  CompositeReadableBuffer readBytes(int length);

  @Override
  void close();
}
