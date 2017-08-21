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
// TODO consistent use of inflate OR decompress terminology
public class GZipInflatingBuffer {
  // Equivalent of unprocessed in standard MessageDeframer
  private final CompositeReadableBuffer compressedData = new CompositeReadableBuffer();

  private static final int GZIP_BASE_HEADER_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  private Inflater inflater = new Inflater(true);
  private State state = State.HEADER;

  // TODO pair state and required length
  private enum State {
    HEADER,
    HEADER_EXTRA, // TODO break down further
    INFLATING,
    INFLATER_NEEDS_INPUT,
    INFLATER_FINISHED,
    TRAILER
  }

  // Test edge cases by setting these to small values
  private static final int INFLATE_BUFFER_SIZE = 512;
  private static final int MAX_BUFFER_SIZE = 1024 * 4;

  private byte[] inflaterBuf = new byte[INFLATE_BUFFER_SIZE];
  private byte[] uncompressedBuf;
  // TODO - decide if need long - probably not since requiredLength in MessageDeframer is int
  private int uncompressedBytesAvailable;

  public void addCompressedBytes(ReadableBuffer buffer) {
    compressedData.addBuffer(buffer);
  }

  /**
   * If min(bytesToRead, MAX_BUFFER_SIZE) of uncompressed data are available, adds them to
   * bufferToWrite and returns true. Otherwise returns false.
   *
   * @param bytesRequested
   * @param bufferToWrite
   * @return
   */
  // TODO: can number of compressed bytes = 0 but still the uncompressed output is available?
  // Maybe possible if locked in inflater's buffer already.
  // TODO - YES, additional compressed bytes can be ZERO with another additional uncompressed
  // block available.
  // TODO - this is basically the wrapping logic for GZip - maybe this should just be in
  // MessageDeframer?
  public int readUncompressedBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite) {
    int bytesToUncompress = Math.min(bytesRequested, MAX_BUFFER_SIZE);
    // have to reallocate each time - just wrap()'ing in a ReadableBuffer doesn't reallocate
    uncompressedBuf = new byte[bytesToUncompress];
    int bytesNeeded;
    int bytesProcessed = 0; // to be removed from flow control

    while ((bytesNeeded = bytesToUncompress - uncompressedBytesAvailable) > 0) {
      switch (state) {
        case HEADER:
          if (compressedData.readableBytes() >= GZIP_BASE_HEADER_SIZE) {
            bytesProcessed += processHeader();
          } else {
            return bytesProcessed;
          }
          break;
        case HEADER_EXTRA:
          // TODO break down HEADER_EXTRA into FEXTRA, FNAME, FCOMMENT, and FHCRC
          throw new AssertionError("Reached HEADER_EXTRA");
        case INFLATING:
          // Pass the body bytes to the inflater
          bytesProcessed += inflate(bytesNeeded);
          break;
        case INFLATER_NEEDS_INPUT:
          if (compressedData.readableBytes() > 0) {
            fill();
          } else {
            return bytesProcessed;
          }
          break;
        case INFLATER_FINISHED:
          inflaterFinished();
          break;
        case TRAILER:
          // TODO get bytes back from inflater if necessary
          if (compressedData.readableBytes() >= GZIP_TRAILER_SIZE) {
            bytesProcessed += processTrailer();
          } else {
            return bytesProcessed;
          }
          break;
        default:
          throw new AssertionError("Invalid state: " + state);
      }
    }

    // TODO assert can't be greater than
    checkState(uncompressedBytesAvailable <= bytesToUncompress, "too many bytes inflated");
    if (uncompressedBytesAvailable == bytesToUncompress) {
      System.out.println("All " + bytesToUncompress + " bytes inflated: " +
              bytesToHex(uncompressedBuf, uncompressedBytesAvailable));
      bufferToWrite.addBuffer(
              ReadableBuffers.wrap(uncompressedBuf, 0, uncompressedBytesAvailable));
      uncompressedBytesAvailable = 0; // reset
    }

    return bytesProcessed;
  }


  // We are requesting bytesToInflate.
  private int inflate(int bytesToInflate) {
    int bytesConsumed = 0;
    try {
      int n = inflater.inflate(uncompressedBuf, uncompressedBytesAvailable, bytesToInflate);
      bytesConsumed += (int) inflater.getBytesRead(); // TODO resolve cast
      if (n == 0) {
        if (inflater.finished()) {
          System.out.println("Finished! Inflater needs input: " + inflater.needsInput());
          // TODO harvest extra bytes given to inflater from trailers/next gzip stream
          // TODO transition to next stream
          state = State.INFLATER_FINISHED;
        } else if (inflater.needsInput()) {
          state = State.INFLATER_NEEDS_INPUT;
        } else {
          throw new AssertionError("inflater stalled");
        }
      } else {
        uncompressedBytesAvailable += n;
        System.out.println("INFLATED (" + n + " bytes = " + uncompressedBytesAvailable
                + " total) " +
                bytesToHex(uncompressedBuf, uncompressedBytesAvailable));
      }
    } catch (DataFormatException e) {
      // TODO properly abort when this happens
      System.out.println("DataFormatException");
      e.printStackTrace(System.out);
    }
    return bytesConsumed;
  }

  private void inflaterFinished() {
    state = State.TRAILER;
    return;
  }

  private void fill() {
    // TODO: remove checkState
    checkState(inflater.needsInput(), "inflater not empty");

    int bytesToAdd = Math.min(compressedData.readableBytes(), INFLATE_BUFFER_SIZE);
    if (bytesToAdd > 0) {
      compressedData.readBytes(inflaterBuf, 0, bytesToAdd);
      System.out.println("Raw bytes read and set as inflater input: " +
              bytesToHex(inflaterBuf, bytesToAdd));
      inflater.setInput(inflaterBuf, 0, bytesToAdd);
      state = State.INFLATING;
    } else {
      throw new AssertionError("no bytes to fill");
    }
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

  private int processHeader() {
    // TODO - handle header CRC
    crc.reset();

    // Check header magic
    if (readUShort() != GZIP_MAGIC) {
      System.out.println("Not in GZIP Format");
      throw new RuntimeException("Not in GZIP format");
      //throw new ZipException("Not in GZIP format");
    }

    // Check compression method
    if (compressedData.readUnsignedByte() != 8) {
      System.out.println("Unsupported compression method");
      throw new RuntimeException("Unsupported compression method");
      //throw new ZipException("Unsupported compression method");
    }
    // Read flags
    int flg = compressedData.readUnsignedByte();
    // Skip MTIME, XFL, and OS fields
    compressedData.readBytes(6); // TODO crc
    int n = 2 + 2 + 6;

    // TODO handle optional extra fields here (some with given length, otherwise zero terminated)
    // + optional header crc
    state = State.INFLATING;

    return n;
  }

  private int processTrailer() {
    byte[] trailer = new byte[GZIP_TRAILER_SIZE]; // TODO: avoid allocation
    compressedData.readBytes(trailer, 0, GZIP_TRAILER_SIZE);

    // TODO check trailer CRC etc

    state = State.HEADER;

    return GZIP_TRAILER_SIZE;
  }

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUShort() {
    int b = compressedData.readUnsignedByte();
    return (compressedData.readUnsignedByte() << 8) | b;
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
    char[] hexChars = new char[len * 3];
    for (int j = 0; j < len; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 3] = hexArray[v >>> 4];
      hexChars[j * 3 + 1] = hexArray[v & 0x0F];
      hexChars[j * 3 + 2] = '-';
    }
    return new String(hexChars);
  }


  //  private void processBody() {
  //    System.out.println("processBody() called");
  //    checkState(inflater.needsInput(), "inflater not empty");
  //
  //    // TODO - avoid allocation here each time
  //    byte[] inputBuf = new byte[nextBlock.readableBytes()];
  //    nextBlock.readBytes(inputBuf, 0, nextBlock.readableBytes());
  //    System.out.println("Raw bytes read and set as inflater input: " + bytesToHex(inputBuf));
  //    inflater.setInput(inputBuf);
  //  }

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

  //  private void processUncompressedBytes() {
  //    System.out.println("processCompressedBytes() called");
  //    switch (state) {
  //      case HEADER:
  //        if (uncompressedData.readableBytes() >= GZIP_BASE_HEADER_SIZE) {
  //          processHeader();
  //        }
  //        break;
  //      case HEADER_EXTRA:
  //        // TODO break down HEADER_EXTRA into FEXTRA, FNAME, FCOMMENT, and FHCRC
  //        throw new AssertionError("Reached HEADER_EXTRA");
  //      case INFLATING:
  //        // Pass the body bytes to the inflater
  //        processBody();
  //        break;
  //      case TRAILER:
  //        // TODO get bytes back from inflater if necessary
  //        processTrailer();
  //        break;
  //      default:
  //        throw new AssertionError("Invalid state: " + state);
  //    }
  //  }

  //  private boolean processCompressedBytes() {
  //    System.out.println("processCompressedBytes() called");
  //    // TODO pending deliveries? should be replaced with buffer fill size, double-check
  //    if (readRequiredBytes()) {
  //      System.out.println("readRequiredBytes() returned true, state = " + state);
  //      switch (state) {
  //        case HEADER:
  //          processHeader();
  //          break;
  //        case HEADER_EXTRA:
  //          // TODO break down HEADER_EXTRA into FEXTRA, FNAME, FCOMMENT, and FHCRC
  //          throw new AssertionError("Reached HEADER_EXTRA");
  //        case BODY:
  //          // Pass the body bytes to the inflater
  //          processBody();
  //          break;
  //        case TRAILER:
  //          // TODO get bytes back from inflater if necessary
  //          processTrailer();
  //          break;
  //        default:
  //          throw new AssertionError("Invalid state: " + state);
  //      }
  //    }
  //    return false;
  //  }

  /**
   * Attempts to read the required bytes into nextFrame.
   *
   * @return {@code true} if all of the required bytes have been read.
   */
  // TODO - delete. This is wrong abstraction (we don't know required length of header extra
  // fields OR compressed body).
  //  private boolean readRequiredBytes() {
  //    // TODO get rid of totalBytesRead here, or drop nextBlock altogether?
  //    int totalBytesRead = 0;
  //    int maxToRead = requiredLength;
  //    if (state == State.INFLATING) {
  //      maxToRead = INFLATE_BUFFER_SIZE;
  //    }
  //    if (nextBlock == null) {
  //      nextBlock = new CompositeReadableBuffer();
  //    }
  //
  //    // Read until the buffer contains all the required bytes.
  //    int missingBytes;
  //    while ((missingBytes = maxToRead - nextBlock.readableBytes()) > 0) {
  //      if (compressedData.readableBytes() == 0) {
  //        if (state == State.INFLATING && totalBytesRead > 0) {
  //          // We've read at least one byte, pass it to inflater
  //          break;
  //        } else {
  //          // No more data is available.
  //          return false;
  //        }
  //      }
  //      int toRead = Math.min(missingBytes, compressedData.readableBytes());
  //      totalBytesRead += toRead;
  //      nextBlock.addBuffer(compressedData.readBytes(toRead));
  //    }
  //    return true;
  //  }
}
