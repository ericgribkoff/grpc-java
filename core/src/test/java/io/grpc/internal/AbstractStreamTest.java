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

import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;

import io.grpc.Compressor;
import io.grpc.internal.AbstractStream.TransportState;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link AbstractStream}.
 */
@RunWith(JUnit4.class)
public class AbstractStreamTest {
  private final StatsTraceContext statsTraceCtx = StatsTraceContext.NOOP;

  @Test
  public void setFullStreamDecompressor_replacesMessageDeframer() {
    AbstractStream stream = new BaseAbstractStream(statsTraceCtx);
    TransportState state = stream.transportState();
    state.setFullStreamDecompressor(new GzipInflatingBuffer());

    // TODO - kind of a no-op...
  }

  /** No-op base class for testing. */
  private static class BaseAbstractStream extends AbstractStream {
    private final TransportState state;
    private final Framer framer;

    private BaseAbstractStream(StatsTraceContext statsTraceCtx) {
      this.state = new BaseTransportState(statsTraceCtx);
      framer = new BaseFramer();
    }

    @Override
    protected Framer framer() {
      return framer;
    }

    @Override
    protected TransportState transportState() {
      return state;
    }

    @Override
    public void request(int numMessages) {}
  }

  private static class BaseFramer implements Framer {
    @Override
    public void writePayload(InputStream message) {

    }

    @Override
    public void flush() {

    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public void close() {

    }

    @Override
    public void dispose() {

    }

    @Override
    public Framer setMessageCompression(boolean enable) {
      return null;
    }

    @Override
    public Framer setCompressor(Compressor compressor) {
      return null;
    }

    @Override
    public void setMaxOutboundMessageSize(int maxSize) {

    }
  }

  private static class BaseTransportState extends AbstractClientStream.TransportState {
    public BaseTransportState(StatsTraceContext statsTraceCtx) {
      super(DEFAULT_MAX_MESSAGE_SIZE, statsTraceCtx);
    }

    @Override
    public void deframeFailed(Throwable cause) {}

    @Override
    public void bytesRead(int processedBytes) {}

    @Override
    public void runOnTransportThread(Runnable r) {
      r.run();
    }
  }
}
