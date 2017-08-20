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

/**
 * Created by ericgribkoff on 8/18/17.
 */
public class GZipInflatingBuffer {
  private final CompositeReadableBuffer compressedData = new CompositeReadableBuffer();
  // TODO - replace uncompressedData with byte[]
  private final CompositeReadableBuffer uncompressedData = new CompositeReadableBuffer();
  // Either gzip header, deflated block, or gzip trailer
  // TODO: remove?
  private CompositeReadableBuffer nextBlock;

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

  private static final int INFLATE_BUFFER_SIZE = 512;

  private byte[] uncompressedBuf;
  // TODO - decide if need long - probably not since requiredLength in MessageDeframer is int
  private int compressedBytesConsumed;
  private int uncompressedBytesAvailable;

  private static final int MAX_BUFFER_SIZE = 1024 * 4;

  public int getAndResetCompressedBytesConsumed() {
    int toReturn = compressedBytesConsumed;
    compressedBytesConsumed = 0;
    return toReturn;
  }

  /**
   * If min(bytesToRead, MAX_BUFFER_SIZE) of uncompressed data are available, adds them to
   * bufferToWrite and returns true. Otherwise returns 0.
   *
   * @param bytesRequested
   * @param bufferToWrite
   * @return
   */
  // TODO: can number of compressed bytes = 0 but still the uncompressed output is available?
  // Maybe possible if locked in inflater's buffer already.
  public boolean readUncompressedBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite) {
    int bytesToUncompress = Math.min(bytesRequested, MAX_BUFFER_SIZE);
    if (uncompressedBuf == null) {
      uncompressedBuf = new byte[bytesToUncompress];
      uncompressedBytesAvailable = 0; // reset
      compressedBytesConsumed = 0; // reset
    }
    // TODO - right now we are giving too much data to the inflater (potentialy, because
    // we might be including the trailers). So we can have inflater.finished() &&
    // !inflater.needsInput(),
    // when we need to pull back some data from the inflater - which should be the TRAILERS
    // plus potentially the next stream.
    // TODO: concatenated streams
    // TODO cannot have compressedData available && inflater.needsInput() ? irrelevant...
    // last condition is ( have data || don't have more data but inflater doesn't need it)
    while (uncompressedBytesAvailable < bytesToUncompress
        && !inflater.finished()
        && (compressedData.readableBytes() > 0 || !inflater.needsInput())) {
      inflate(bytesToUncompress - uncompressedBytesAvailable);
    }
    // TODO assert can't be greater than
    if (uncompressedBytesAvailable == bytesToUncompress) {
      bufferToWrite.addBuffer(ReadableBuffers.wrap(uncompressedBuf));
      uncompressedBuf = null;
      return true;
    } else {
      return false;
    }
  }

  public void addCompressedBytes(ReadableBuffer buffer) {
    compressedData.addBuffer(buffer);
  }

  //  public boolean uncompressedBytesReady(int bytesRequested) {
  //    checkState(bytesRequested >= 0, "bytes must be non-negative");
  //    int uncompressedBytes = uncompressedData.readableBytes();
  //    if (uncompressedBytes >= bytesRequested) {
  //      return true;
  //    } else {
  //      // TODO - right now we are giving too much data to the inflater (potentialy, because
  //      // we might be including the trailers). So we can have inflater.finished() &&
  //      // !inflater.needsInput(),
  //      // when we need to pull back some data from the inflater - which should be the TRAILERS
  //      // plus potentially the next stream.
  //      // TODO: concatenated streams
  //      while (uncompressedData.readableBytes() < bytesRequested
  //          && !inflater.finished()
  //          && (compressedData.readableBytes() > 0 || !inflater.needsInput())) {
  //        inflate(bytesRequested);
  //      }
  //      return uncompressedData.readableBytes() >= bytesRequested;
  //    }
  //  }

  private void inflate(int bytesToInflate) {
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
      //      // TODO - use bytesRequested to avoid this re-allocation every time
      //      byte[] decompressedBuf = new byte[INFLATE_BUFFER_SIZE];
      try {
        if (inflater.finished()) {
          System.out.println("Finished! Inflater needs input: " + inflater.needsInput());
          state = State.TRAILER;
        }
        int n = inflater.inflate(uncompressedBuf, uncompressedBytesAvailable, bytesToInflate);
        uncompressedBytesAvailable += n;
        System.out.println("INFLATED (" + n + " bytes) " +
            bytesToHex(uncompressedBuf, uncompressedBytesAvailable));
        //        uncompressedData.addBuffer(ReadableBuffers.wrap(uncompressedBuf, 0, n));
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
    // TODO get rid of totalBytesRead here, or drop nextBlock altogether?
    int totalBytesRead = 0;
    int maxToRead = requiredLength;
    if (state == State.BODY) {
      maxToRead = INFLATE_BUFFER_SIZE;
    }
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

  // TODO - remove
  private static final char[] hexArray = "0123456789ABCDEF".toCharArray();

  /** Javadoc. */
  // TODO - remove
  public static String bytesToHex(byte[] bytes) {
    return bytesToHex(bytes, bytes.length);
  }

  /** Javadoc. */
  // TODO - remove
  public static String bytesToHex(byte[] bytes, int len) {
    char[] hexChars = new char[len * 2];
    for (int j = 0; j < len; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }
}
