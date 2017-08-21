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
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Created by ericgribkoff on 8/18/17.
 *
 * <p>Some GZip parsing code adapted from java.util.zip.GZIPInputStream.
 */
@NotThreadSafe
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
    //    INFLATER_FINISHED, - just go directly to TRAILER
    TRAILER
  }

  private static final int INFLATE_BUFFER_SIZE = 512;
  private static final int MAX_BUFFER_SIZE = 1024 * 4;

  private byte[] inflaterBuf = new byte[INFLATE_BUFFER_SIZE];
  private int inflaterBufLen;

  private byte[] uncompressedBuf;
  private int uncompressedBufWriterIndex;

  public boolean isStalled() {
    // TODO - not quite right, but want to verify finish state.
    return compressedData.readableBytes() == 0 && state != State.INFLATING;
  }

  public boolean hasPartialData() {
    return compressedData.readableBytes() != 0 || inflater.getRemaining() != 0;
  }

  public void addCompressedBytes(ReadableBuffer buffer) {
    System.out.println("Adding " + buffer.readableBytes() + " bytes to compressedData");
    compressedData.addBuffer(buffer);
  }

  int bytesConsumed = 0; // to be removed from flow control

  public int getAndResetCompressedBytesConsumed() {
    int ret = bytesConsumed;
    bytesConsumed = 0;
    return ret;
  }

  /**
   * If min(bytesToRead, MAX_BUFFER_SIZE) of uncompressed data are available, adds them to
   * bufferToWrite and returns true. Otherwise returns false.
   *
   * @param bytesRequested
   * @param bufferToWrite
   * @return the number of bytes read into bufferToWrite
   */
  public int readUncompressedBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite) {
    int totalBytesNeeded =
        Math.min(
            uncompressedBufWriterIndex + bytesRequested,
            MAX_BUFFER_SIZE);

    System.out.println("totalBytesNeeded: " + totalBytesNeeded);

    if (uncompressedBuf == null) {
      uncompressedBuf = new byte[totalBytesNeeded];
    }


    System.out.println("uncompressedBufWriterIndex (before): " + uncompressedBufWriterIndex);

    int bytesNeeded;
    // TODO - better stopping condition for this loop (embedded returns are confusing)
    while ((bytesNeeded = totalBytesNeeded - uncompressedBufWriterIndex) > 0) {
      System.out.println("State: " + state);
      switch (state) {
        case HEADER:
          if (inflater.getRemaining() + compressedData.readableBytes() >= GZIP_BASE_HEADER_SIZE) {
            bytesConsumed += processHeader();
          } else {
            System.out.println("Returning...");
            System.out.println("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
            System.out.println(inflater.getRemaining());
            System.out.println(compressedData.readableBytes());
            return 0;
          }
          break;
        case HEADER_EXTRA:
          // TODO break down HEADER_EXTRA into FEXTRA, FNAME, FCOMMENT, and FHCRC
          throw new AssertionError("Reached HEADER_EXTRA");
        case INFLATING:
          // Pass the body bytes to the inflater
          inflate(bytesNeeded);
          break;
        case INFLATER_NEEDS_INPUT:
          if (compressedData.readableBytes() > 0) {
            fill();
          } else {
            return 0;
          }
          break;
        case TRAILER:
          if (inflater.getRemaining() + compressedData.readableBytes() >= GZIP_TRAILER_SIZE) {
            bytesConsumed += processTrailer();
          } else {
            return 0;
          }
          break;
        default:
          throw new AssertionError("Invalid state: " + state);
      }
    }

    System.out.println("uncompressedBufWriterIndex (after): " + uncompressedBufWriterIndex);

    // TODO assert can't be greater than
    // TODO BROKEN doesn't take into account wrapping
    checkState(uncompressedBufWriterIndex <= totalBytesNeeded, "too many bytes inflated");
    if (uncompressedBufWriterIndex == totalBytesNeeded) {
//      System.out.println("All " + bytesToUncompress + " bytes inflated: " +
//              bytesToHex(uncompressedBuf, uncompressedBufWriterIndex));
      bufferToWrite.addBuffer(
              ReadableBuffers.wrap(uncompressedBuf, 0, uncompressedBufWriterIndex));
      uncompressedBufWriterIndex = 0; // reset
      uncompressedBuf = null;
      return totalBytesNeeded;
    }

    return 0;
  }


  // We are requesting bytesToInflate.
  private int inflate(int bytesToInflate) {
    System.out.println("bytesToInflate: " + bytesToInflate);
    int bytesAlreadyConsumed = inflater.getTotalIn();
    try {
      int n = inflater.inflate(uncompressedBuf, uncompressedBufWriterIndex, bytesToInflate);
      bytesConsumed += inflater.getTotalIn() - bytesAlreadyConsumed;
      if (n == 0) {
        if (inflater.finished()) {
          System.out.println("Finished! Inflater needs input: " + inflater.needsInput());
          System.out.println("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
          state = State.TRAILER;
        } else if (inflater.needsInput()) {
          state = State.INFLATER_NEEDS_INPUT;
        } else {
          throw new AssertionError("inflater stalled");
        }
      } else {
        uncompressedBufWriterIndex += n;
        //        System.out.println("INFLATED (" + n + " bytes = " + uncompressedBufWriterIndex
        //                + " total) " +
        //                bytesToHex(uncompressedBuf, uncompressedBufWriterIndex));
      }
    } catch (DataFormatException e) {
      // TODO properly abort when this happens
      System.out.println("DataFormatException");
      e.printStackTrace(System.out);
    }
    return bytesConsumed;
  }

  private void fill() {
    int bytesToAdd = Math.min(compressedData.readableBytes(), INFLATE_BUFFER_SIZE);
    if (bytesToAdd > 0) {
      compressedData.readBytes(inflaterBuf, 0, bytesToAdd);
      //      System.out.println("Raw bytes read and set as inflater input: " +
      //              bytesToHex(inflaterBuf, bytesToAdd));
      inflaterBufLen = bytesToAdd;
      inflater.setInput(inflaterBuf, 0, inflaterBufLen);
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

  private byte[] tmpBuffer = new byte[128]; // for skipping/parsing(?) bytes

  private int processHeader() {
    // TODO - handle header CRC
    crc.reset();

    int bytesRemainingInInflater = inflater.getRemaining();
    int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;
    int bytesToGetFromInflater = Math.min(GZIP_BASE_HEADER_SIZE, bytesRemainingInInflater);

    System.out.println("bytesRemainingInInflater: " + bytesRemainingInInflater);
    System.out.println("bytesToGetFromInflater: " + bytesToGetFromInflater);
    System.out.println("compressedData.readableBytes(): " + compressedData.readableBytes());

    inflater.reset(); // TODO - make this more obviously required here!

    if (bytesToGetFromInflater > 0) {
      System.out.println("Setting inflater input: start=" + (inflaterBufHeaderStartIndex + bytesToGetFromInflater) + " len=" +
          (bytesRemainingInInflater - bytesToGetFromInflater));
      inflater.setInput(
          inflaterBuf,
          inflaterBufHeaderStartIndex + bytesToGetFromInflater,
          bytesRemainingInInflater - bytesToGetFromInflater);
    }

    for (int i = 0; i < bytesToGetFromInflater; i++) {
      tmpBuffer[i] = inflaterBuf[inflaterBufHeaderStartIndex + i];
    }

    int bytesToGetFromCompressedData = GZIP_BASE_HEADER_SIZE - bytesToGetFromInflater;
    System.out.println("bytesToGetFromCompressedData: " + bytesToGetFromCompressedData);
    compressedData.readBytes(tmpBuffer, bytesToGetFromInflater, bytesToGetFromCompressedData);

    // TODO check everything

    // Check header magic
    if (readUnsignedShort(tmpBuffer[0], tmpBuffer[1]) != GZIP_MAGIC) {
      System.out.println("Not in GZIP Format");
      throw new RuntimeException("Not in GZIP format");
      //throw new ZipException("Not in GZIP format");
    }

    // Check compression method
    if (readUnsignedByte(tmpBuffer[2]) != 8) {
      System.out.println("Unsupported compression method");
      throw new RuntimeException("Unsupported compression method");
      //throw new ZipException("Unsupported compression method");
    }

    //    // Read flags
    //    int flg = compressedData.readUnsignedByte();
    //    // Skip MTIME, XFL, and OS fields
    //    compressedData.readBytes(6); // TODO crc
    int n = 2 + 2 + 6;

    // TODO handle optional extra fields here (some with given length, otherwise zero terminated)
    // + optional header crc

    state = State.INFLATING;
    return n; // TODO: should be variable
  }

  private int readUnsignedByte(byte b) {
    return b & 0xFF;
  }

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUnsignedShort(byte b1, byte b2) {
    return (readUnsignedByte(b2) << 8) | readUnsignedByte(b1);
  }

  private int processTrailer() {
    int bytesRemainingInInflater = inflater.getRemaining();
    int inflaterBufTrailerStartIndex = inflaterBufLen - bytesRemainingInInflater;
    int bytesToGetFromInflater = Math.min(GZIP_TRAILER_SIZE, bytesRemainingInInflater);

    System.out.println("bytesRemainingInInflater: " + bytesRemainingInInflater);
    System.out.println("bytesToGetFromInflater: " + bytesToGetFromInflater);
    System.out.println("compressedData.readableBytes(): " + compressedData.readableBytes());
    if (bytesToGetFromInflater > 0) {
      System.out.println("Setting inflater input: start=" + (inflaterBufTrailerStartIndex + bytesToGetFromInflater) + " len=" +
          (bytesRemainingInInflater - bytesToGetFromInflater));
      inflater.setInput(
          inflaterBuf,
          inflaterBufTrailerStartIndex + bytesToGetFromInflater,
          bytesRemainingInInflater - bytesToGetFromInflater);
      System.out.println("Remaining in inflater: " + inflater.getRemaining());
    }

    // TODO check CRC
    for (int i = 0; i < bytesToGetFromInflater; i++) {
      tmpBuffer[i] = inflaterBuf[inflaterBufTrailerStartIndex + i];
    }
    int bytesToGetFromCompressedData = GZIP_TRAILER_SIZE - bytesToGetFromInflater;
    System.out.println("bytesToGetFromCompressedData: " + bytesToGetFromCompressedData);
    compressedData.readBytes(tmpBuffer, bytesToGetFromInflater, bytesToGetFromCompressedData);

    state = State.HEADER;

    return GZIP_TRAILER_SIZE;
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
    return new String(hexChars) + " (" + len + " bytes)";
  }
}
