/*
 * Copyright 2014, gRPC Authors All rights reserved.
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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.io.ByteStreams;
import io.grpc.Attributes;
import io.grpc.Codec;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server stream for a Netty HTTP2 transport. Must only be called from the sending application
 * thread.
 */
class NettyServerStream extends AbstractServerStream {
  private static final Logger log = Logger.getLogger(NettyServerStream.class.getName());

  private final Sink sink = new Sink();
  private final TransportState state;
  private final Channel channel;
  private final WriteQueue writeQueue;
  private final Attributes attributes;
  private final String authority;

  public NettyServerStream(Channel channel, TransportState state, Attributes transportAttrs,
      String authority, StatsTraceContext statsTraceCtx, boolean fullStreamCompression) {
    super(new NettyWritableBufferAllocator(channel.alloc()), statsTraceCtx, fullStreamCompression);
    this.state = checkNotNull(state, "transportState");
    this.channel = checkNotNull(channel, "channel");
    this.writeQueue = state.handler.getWriteQueue();
    this.attributes = checkNotNull(transportAttrs);
    this.authority = authority;
  }

  @Override
  protected void readBytesFromBufferToStream(WritableBuffer buffer, OutputStream os)
      throws IOException {
    if (buffer != null) {
      ByteBuf bytebuf = ((NettyWritableBuffer) buffer).bytebuf();
      System.out.println("Readable bytes: " + bytebuf.readableBytes());
      byte[] rawBytes = new byte[bytebuf.readableBytes()];
      bytebuf.readBytes(rawBytes);
//      System.out.println("Raw bytes:" + bytesToHex(rawBytes));
      bytebuf.resetReaderIndex();
      System.out.println("Readable bytes: " + bytebuf.readableBytes());
      bytebuf.readBytes(os, bytebuf.readableBytes());

      Codec gzip = new Codec.Gzip();
      ByteArrayOutputStream byteOs = new ByteArrayOutputStream(rawBytes.length);
      OutputStream compressedOs = gzip.compress(byteOs);
      compressedOs.write(rawBytes);
      compressedOs.close();

      System.out.println("Compressed bytes: " + byteOs.toByteArray().length);

      //      System.out.println("Compressed bytes: " + bytesToHex(byteOs.toByteArray()));
      //
      //      System.out.println(
      //          "Uncompressed bytes: "
      //              + bytesToHex(
      //                  ByteStreams.toByteArray(
      //                      gzip.decompress(new ByteArrayInputStream(byteOs.toByteArray())))));

    }
  }

  private static final char[] hexArray = "0123456789ABCDEF".toCharArray();

  /** Javadoc. */
  // TODO - remove
  public static String bytesToHex(byte[] bytes) {
    return bytesToHex(bytes, bytes.length);
  }

  /** Javadoc. */
  // TODO - remove
  public static String bytesToHex(byte[] bytes, int len) {
    char[] hexChars = new char[len * 3];
    for (int j = 0; j < len; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 3] = hexArray[v >>> 4];
      hexChars[j * 3 + 1] = hexArray[v & 0x0F];
      hexChars[j * 3 + 2] = '-';
    }
    return new String(hexChars) + " (" + len + " bytes)";
  }

  @Override
  protected TransportState transportState() {
    return state;
  }

  @Override
  protected Sink abstractServerStreamSink() {
    return sink;
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  @Override
  public String getAuthority() {
    return authority;
  }

  private class Sink implements AbstractServerStream.Sink {
    @Override
    public void request(final int numMessages) {
      if (channel.eventLoop().inEventLoop()) {
        // Processing data read in the event loop so can call into the deframer immediately
        transportState().requestMessagesFromDeframer(numMessages);
      } else {
        channel.eventLoop().execute(new Runnable() {
          @Override
          public void run() {
            transportState().requestMessagesFromDeframer(numMessages);
          }
        });
      }
    }

    @Override
    public void writeHeaders(Metadata headers) {
      writeQueue.enqueue(new SendResponseHeadersCommand(transportState(),
          Utils.convertServerHeaders(headers), false),
          true);
    }

    @Override
    public void writeFrame(WritableBuffer frame, boolean flush) {
      if (frame == null) {
        writeQueue.scheduleFlush();
        return;
      }
      ByteBuf bytebuf = ((NettyWritableBuffer) frame).bytebuf();
      final int numBytes = bytebuf.readableBytes();
      // Add the bytes to outbound flow control.
      onSendingBytes(numBytes);
      writeQueue.enqueue(
          new SendGrpcFrameCommand(transportState(), bytebuf, false),
          channel.newPromise().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
              // Remove the bytes from outbound flow control, optionally notifying
              // the client that they can send more bytes.
              transportState().onSentBytes(numBytes);
            }
          }), flush);
    }

    @Override
    public void writeTrailers(Metadata trailers, boolean headersSent) {
      Http2Headers http2Trailers = Utils.convertTrailers(trailers, headersSent);
      writeQueue.enqueue(
          new SendResponseHeadersCommand(transportState(), http2Trailers, true), true);
    }

    @Override
    public void cancel(Status status) {
      writeQueue.enqueue(new CancelServerStreamCommand(transportState(), status), true);
    }
  }

  /** This should only called from the transport thread. */
  public static class TransportState extends AbstractServerStream.TransportState
      implements StreamIdHolder {
    private final Http2Stream http2Stream;
    private final NettyServerHandler handler;
    private final EventLoop eventLoop;

    public TransportState(NettyServerHandler handler, EventLoop eventLoop, Http2Stream http2Stream,
        int maxMessageSize, StatsTraceContext statsTraceCtx) {
      super(maxMessageSize, statsTraceCtx);
      this.http2Stream = checkNotNull(http2Stream, "http2Stream");
      this.handler = checkNotNull(handler, "handler");
      this.eventLoop = eventLoop;
    }

    @Override
    public void runOnTransportThread(final Runnable r) {
      if (eventLoop.inEventLoop()) {
        r.run();
      } else {
        eventLoop.execute(r);
      }
    }

    @Override
    public void bytesRead(int processedBytes) {
      handler.returnProcessedBytes(http2Stream, processedBytes);
      handler.getWriteQueue().scheduleFlush();
    }

    @Override
    public void deframeFailed(Throwable cause) {
      log.log(Level.WARNING, "Exception processing message", cause);
      Status status = Status.fromThrowable(cause);
      transportReportStatus(status);
      handler.getWriteQueue().enqueue(new CancelServerStreamCommand(this, status), true);
    }

    void inboundDataReceived(ByteBuf frame, boolean endOfStream) {
      super.inboundDataReceived(new NettyReadableBuffer(frame.retain()), endOfStream);
    }

    @Override
    public int id() {
      return http2Stream.id();
    }
  }
}
