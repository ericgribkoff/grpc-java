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

import static com.google.common.base.Preconditions.checkState;

import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Created by ericgribkoff on 8/17/17. */
public class GzipInflatingCompositeBuffer implements CompositeBuffer {
  private final MessageDeframer.Listener listener;

  public GzipInflatingCompositeBuffer(MessageDeframer.Listener listener) {
    this.listener = listener;
  }

  private final CompositeReadableBuffer compressedData = new CompositeReadableBuffer();
  private final CompositeReadableBuffer uncompressedData = new CompositeReadableBuffer();

  private static final int INFLATE_BUFFER_SIZE = 512;

  // Either gzip header, deflated block, or gzip trailer
  private CompositeReadableBuffer nextBlock;

  @Override
  public void addBuffer(ReadableBuffer buffer) {
    compressedData.addBuffer(buffer);
  }

  @Override
  public int readableBytes() {
    int uncompressedBytes = uncompressedData.readableBytes();
    if (uncompressedBytes > 0) {
      return uncompressedBytes;
    } else {
      // TODO - right now we are giving too much data to the inflater (potentialy, because
      // we might be including the trailers). So we can have inflater.finished() &&
      // !inflater.needsInput(),
      // when we need to pull back some data from the inflater - which should be the TRAILERS
      // plus potentially the next stream.
      // TODO: concatenated streams
      while (uncompressedData.readableBytes() == 0
          && !inflater.finished()
          && (compressedData.readableBytes() > 0 || !inflater.needsInput())) {
        inflate();
      }
      return uncompressedData.readableBytes();
    }
  }

  @Override
  public CompositeReadableBuffer readBytes(int length) {
    return uncompressedData.readBytes(length);
  }

  @Override
  public void close() {
    compressedData.close();
  }

  private static final int GZIP_BASE_HEADER_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  private Inflater inflater = new Inflater(true);
  private State state = State.HEADER;

  private int requiredLength = GZIP_BASE_HEADER_SIZE;

  // TODO pair state and required length
  private enum State {
    HEADER,
    HEADER_EXTRA,
    BODY,
    TRAILER
  }

  private void inflate() {
    // But we can also have inflater.needsInput() && inflater.finished(), or !inflated.needsInput()
    // && inflater.finished().
    if (inflater.finished()) {
      // TODO harvest extra bytes given to inflater from trailers/next gzip stream
      state = State.TRAILER;
      requiredLength = GZIP_TRAILER_SIZE;
      processCompressedBytes();
    } else if (inflater.needsInput()) {
      processCompressedBytes();
    } else {
      byte[] decompressedBuf = new byte[INFLATE_BUFFER_SIZE];
      try {
        if (inflater.finished()) {
          System.out.println("Finished! Inflater needs input: " + inflater.needsInput());
          state = State.TRAILER;
        }
        int n = inflater.inflate(decompressedBuf, 0, INFLATE_BUFFER_SIZE);
        System.out.println("INFLATED (" + n + " bytes) " + bytesToHex(decompressedBuf));
        uncompressedData.addBuffer(ReadableBuffers.wrap(decompressedBuf, 0, n));
      } catch (DataFormatException e) {
        System.out.println("DataFormatException");
        e.printStackTrace(System.out);
      }
    }
  }

  private int processCompressedBytes() {
    System.out.println("processCompressedBytes() called");
    // TODO pending deliveries? should be replaced with buffer fill size, double-check
    if (readRequiredBytes()) {
      System.out.println("readRequiredBytes() returned true, state = " + state);
      switch (state) {
        case HEADER:
          processHeader();
          break;
        case HEADER_EXTRA:
          // TODO break down HEADER_EXTRA into FEXTRA, FNAME, FCOMMENT, and FHCRC
          throw new AssertionError("Reached HEADER_EXTRA");
        case BODY:
          // Pass the body bytes to the inflater
          processBody();
          break;
        case TRAILER:
          // TODO get bytes back from inflater if necessary
          processTrailer();
          break;
        default:
          throw new AssertionError("Invalid state: " + state);
      }
    }
    return 0;
  }

  /**
   * Attempts to read the required bytes into nextFrame.
   *
   * @return {@code true} if all of the required bytes have been read.
   */
  private boolean readRequiredBytes() {
    int totalBytesRead = 0;
    int maxToRead = requiredLength;
    if (state == State.BODY) {
      maxToRead = INFLATE_BUFFER_SIZE;
    }
    try {
      if (nextBlock == null) {
        nextBlock = new CompositeReadableBuffer();
      }

      // Read until the buffer contains all the required bytes.
      int missingBytes;
      while ((missingBytes = maxToRead - nextBlock.readableBytes()) > 0) {
        if (compressedData.readableBytes() == 0) {
          if (state == State.BODY && totalBytesRead > 0) {
            // We've read at least one byte, pass it to inflater
            break;
          } else {
            // No more data is available.
            return false;
          }
        }
        int toRead = Math.min(missingBytes, compressedData.readableBytes());
        totalBytesRead += toRead;
        nextBlock.addBuffer(compressedData.readBytes(toRead));
      }
      return true;
    } finally {
      if (totalBytesRead > 0) {
        // TODO get a listener
        listener.bytesRead(totalBytesRead);
      }
    }
  }

  private static final char[] hexArray = "0123456789ABCDEF".toCharArray();

  /** Javadoc. */
  public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

  /** GZIP header magic number. */
  public static final int GZIP_MAGIC = 0x8b1f;

  /*
   * File header flags.
   */
  private static final int FTEXT = 1; // Extra text
  private static final int FHCRC = 2; // Header CRC
  private static final int FEXTRA = 4; // Extra field
  private static final int FNAME = 8; // File name
  private static final int FCOMMENT = 16; // File comment

  /** CRC-32 for uncompressed data. */
  protected CRC32 crc = new CRC32();

  private void processHeader() {
    crc.reset();

    // TODO - handle CRC (here it's just for header, also need it for compressed bytes)

    // Check header magic
    if (readUShort() != GZIP_MAGIC) {
      System.out.println("Not in GZIP Format");
      throw new RuntimeException("Not in GZIP format");
      //throw new ZipException("Not in GZIP format");
    }

    // Check compression method
    if (nextBlock.readUnsignedByte() != 8) {
      System.out.println("Unsupported compression method");
      throw new RuntimeException("Unsupported compression method");
      //throw new ZipException("Unsupported compression method");
    }
    // Read flags
    int flg = nextBlock.readUnsignedByte();
    // Skip MTIME, XFL, and OS fields
    nextBlock.readBytes(6); // TODO crc
    int n = 2 + 2 + 6;

    // TODO handle optional extra fields here (some with given length, otherwise zero terminated)
    // + optional header crc
    state = State.BODY;
  }

  private void processBody() {
    System.out.println("processBody() called");
    checkState(inflater.needsInput(), "inflater not empty");

    // TODO - avoid allocation here each time
    byte[] inputBuf = new byte[nextBlock.readableBytes()];
    nextBlock.readBytes(inputBuf, 0, nextBlock.readableBytes());
    System.out.println("Raw bytes read and set as inflater input: " + bytesToHex(inputBuf));
    inflater.setInput(inputBuf);
  }

  private void processTrailer() {
    byte[] trailer = new byte[GZIP_TRAILER_SIZE];
    nextBlock.readBytes(trailer, 0, GZIP_TRAILER_SIZE);

    // TODO check trailer CRC etc

    state = State.HEADER;
    requiredLength = GZIP_BASE_HEADER_SIZE;
  }

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUShort() {
    int b = nextBlock.readUnsignedByte();
    return (nextBlock.readUnsignedByte() << 8) | b;
  }

  //  /*
  // * Reads GZIP member header and returns the total byte number
  // * of this member header.
  // */
  //  private boolean readHeader() throws IOException {
  //    crc.reset();
  //
  //    // Check header magic
  //    if (readUShort(in) != GZIP_MAGIC) {
  //      throw new ZipException("Not in GZIP format");
  //    }
  //    // Check compression method
  //    if (readUByte(in) != 8) {
  //      throw new ZipException("Unsupported compression method");
  //    }
  //    // Read flags
  //    int flg = readUByte(in);
  //    // Skip MTIME, XFL, and OS fields
  //    skipBytes(in, 6);
  //    int n = 2 + 2 + 6;
  //    // Skip optional extra field
  //    if ((flg & FEXTRA) == FEXTRA) {
  //      int m = readUShort(in);
  //      skipBytes(in, m);
  //      n += m + 2;
  //    }
  //    // Skip optional file name
  //    if ((flg & FNAME) == FNAME) {
  //      do {
  //        n++;
  //      } while (readUByte(in) != 0);
  //    }
  //    // Skip optional file comment
  //    if ((flg & FCOMMENT) == FCOMMENT) {
  //      do {
  //        n++;
  //      } while (readUByte(in) != 0);
  //    }
  //    // Check optional header CRC
  //    if ((flg & FHCRC) == FHCRC) {
  //      int v = (int)crc.getValue() & 0xffff;
  //      if (readUShort(in) != v) {
  //        throw new ZipException("Corrupt GZIP header");
  //      }
  //      n += 2;
  //    }
  //    crc.reset();
  //    return n;
  //  }

  //  /*
  //   * Reads GZIP member trailer and returns true if the eos
  //   * reached, false if there are more (concatenated gzip
  //   * data set)
  //   */
  //  private boolean readTrailer() throws IOException {
  //    InputStream in = this.in;
  //    int n = inf.getRemaining();
  //    if (n > 0) {
  //      in = new SequenceInputStream(
  //              new ByteArrayInputStream(buf, len - n, n),
  //              new FilterInputStream(in) {
  //                public void close() throws IOException {}
  //              });
  //    }
  //    // Uses left-to-right evaluation order
  //    if ((readUInt(in) != crc.getValue()) ||
  //            // rfc1952; ISIZE is the input size modulo 2^32
  //            (readUInt(in) != (inf.getBytesWritten() & 0xffffffffL)))
  //      throw new ZipException("Corrupt GZIP trailer");
  //
  //    // If there are more bytes available in "in" or
  //    // the leftover in the "inf" is > 26 bytes:
  //    // this.trailer(8) + next.header.min(10) + next.trailer(8)
  //    // try concatenated case
  //    if (this.in.available() > 0 || n > 26) {
  //      int m = 8;                  // this.trailer
  //      try {
  //        m += readHeader(in);    // next.header
  //      } catch (IOException ze) {
  //        return true;  // ignore any malformed, do nothing
  //      }
  //      inf.reset();
  //      if (n > m)
  //        inf.setInput(buf, len - n + m, n - m);
  //      return false;
  //    }
  //    return true;
  //  }

  /*
   * Reads unsigned integer in Intel byte order.
   */
  //  private long readUInt(InputStream in) throws IOException {
  //    long s = readUShort(in);
  //    return ((long)readUShort(in) << 16) | s;
  //  }
  //
  //  /*
  //   * Reads unsigned short in Intel byte order.
  //   */
  //  private int readUShort(InputStream in) throws IOException {
  //    int b = readUByte(in);
  //    return (readUByte(in) << 8) | b;
  //  }
  //
  //  /*
  //   * Reads unsigned byte.
  //   */
  //  private int readUByte(InputStream in) throws IOException {
  //    int b = in.read();
  //    if (b == -1) {
  //      throw new EOFException();
  //    }
  //    if (b < -1 || b > 255) {
  //      // Report on this.in, not argument in; see read{Header, Trailer}.
  //      throw new IOException(this.getClass().getName()
  //          + ".read() returned value out of range -1..255: " + b);
  //    }
  //    return b;
  //  }
  //
  //  private byte[] tmpbuf = new byte[128];
  //
  //  /*
  //   * Skips bytes of input data blocking until all bytes are skipped.
  //   * Does not assume that the input stream is capable of seeking.
  //   */
  //  private void skipBytes(InputStream in, int n) throws IOException {
  //    while (n > 0) {
  //      int len = in.read(tmpbuf, 0, n < tmpbuf.length ? n : tmpbuf.length);
  //      if (len == -1) {
  //        throw new EOFException();
  //      }
  //      n -= len;
  //    }
  //  }

}
