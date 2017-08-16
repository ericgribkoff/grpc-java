/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import io.grpc.Compressor;
import java.io.InputStream;
import javax.annotation.Nullable;

/** A compressed stream framer. */
public class CompressedStreamFramer implements Framer, MessageFramer.Sink {
  private final MessageFramer.Sink savedSink;
  private MessageFramer delegatedFramer;

  public CompressedStreamFramer(MessageFramer.Sink sink, WritableBufferAllocator bufferAllocator,
      StatsTraceContext statsTraceCtx) {
    this.savedSink = sink;
    this.delegatedFramer = new MessageFramer(this, bufferAllocator, statsTraceCtx);
  }

  @Override
  public void deliverFrame(@Nullable WritableBuffer frame, boolean endOfStream, boolean flush) {
    System.out.println("I can queue these frames!");
    savedSink.deliverFrame(frame, endOfStream, flush);
  }

  @Override
  public void writePayload(InputStream message) {
    delegatedFramer.writePayload(message);
  }

  @Override
  public void flush() {
    delegatedFramer.flush();
  }

  @Override
  public boolean isClosed() {
    return delegatedFramer.isClosed();
  }

  @Override
  public void close() {
    delegatedFramer.close();
  }

  @Override
  public void dispose() {
    delegatedFramer.dispose();
  }

  @Override
  public Framer setMessageCompression(boolean enable) {
    this.delegatedFramer = delegatedFramer.setMessageCompression(enable);
    return this;
  }

  @Override
  public Framer setCompressor(Compressor compressor) {
    this.delegatedFramer = delegatedFramer.setCompressor(compressor);
    return this;
  }

  @Override
  public void setMaxOutboundMessageSize(int maxSize) {
    delegatedFramer.setMaxOutboundMessageSize(maxSize);
  }
}
