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

import java.io.Closeable;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Created by ericgribkoff on 8/18/17.
 *
 * <p>Some gzip parsing code adapted from java.util.zip.GZIPInputStream.
 */
@NotThreadSafe
public class GZipInflatingBuffer implements Closeable {

  private static final int GZIP_BASE_HEADER_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  private static final int USHORT_LEN = 2;

  /** GZIP header magic number. */
  public static final int GZIP_MAGIC = 0x8b1f;

  /*
   * File header flags. (FTEXT is ignored)
   */
  private static final int FHCRC = 2; // Header CRC
  private static final int FEXTRA = 4; // Extra field
  private static final int FNAME = 8; // File name
  private static final int FCOMMENT = 16; // File comment

  private enum State {
    HEADER,
    HEADER_EXTRA_LEN,
    HEADER_EXTRA,
    HEADER_NAME,
    HEADER_COMMENT,
    HEADER_CRC,
    INITIALIZE_INFLATER,
    INFLATING,
    INFLATER_NEEDS_INPUT,
    TRAILER
  }

  private static final int INFLATE_BUFFER_SIZE = 512;
  private static final int MAX_BUFFER_SIZE = 1024 * 4;

  private final CompositeReadableBuffer compressedData = new CompositeReadableBuffer();
  private Inflater inflater = new Inflater(true);
  private State state = State.HEADER;

  private byte[] inflaterBuf = new byte[INFLATE_BUFFER_SIZE];
  private int inflaterBufLen;

  private byte[] uncompressedBuf;
  private int uncompressedBufWriterIndex;

  private int gzipHeaderFlag;
  private int headerExtraToRead;

  private boolean closed = false;

  int bytesConsumed = 0; // tracks compressed bytes to be removed from flow control

  /** CRC-32 for uncompressed data. */
  protected CRC32 crc = new CRC32();

  private final CrcCompositeReadableBuffer nextFrame =
      new CrcCompositeReadableBuffer();

  private class CrcCompositeReadableBuffer {

    private int readableBytes() {
      return inflater.getRemaining() + compressedData.readableBytes();
    }

    private int readUnsignedByte() {
      int bytesRemainingInInflater = inflater.getRemaining();
      int b;
      if (bytesRemainingInInflater > 0) {
        int bytesToGetFromInflater = 1;
        int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;
        loggingHack("bytesToGetFromInflater: " + bytesToGetFromInflater);
        loggingHack(
            "Setting inflater input: start="
                + (inflaterBufHeaderStartIndex + bytesToGetFromInflater)
                + " len="
                + (bytesRemainingInInflater - bytesToGetFromInflater));
        System.out.println(
            "From inflaterBuf: inflaterBufHeaderStartIndex="
                + inflaterBufHeaderStartIndex
                + " bytesToGetFromInflater="
                + bytesToGetFromInflater);

        b = inflaterBuf[inflaterBufHeaderStartIndex] & 0xFF;

        inflater.reset();
        inflater.setInput(
            inflaterBuf,
            inflaterBufHeaderStartIndex + 1,
            bytesRemainingInInflater - 1);
      } else {
        b = compressedData.readUnsignedByte();
      }
      loggingHack("Returning unsignedByte = " + bytesToHex((byte) b) + " (" + b + ")");
      crc.update(b);
      bytesConsumed += 1;
      return b;
    }

    private void skipBytes(int length) {
      int bytesToSkip = length;
      int bytesRemainingInInflater = inflater.getRemaining();

      if (bytesRemainingInInflater > 0) {
        int bytesToGetFromInflater = Math.min(bytesRemainingInInflater, bytesToSkip);

        int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;
        loggingHack("bytesToGetFromInflater: " + bytesToGetFromInflater);
        loggingHack(
            "Setting inflater input: start="
                + (inflaterBufHeaderStartIndex + bytesToGetFromInflater)
                + " len="
                + (bytesRemainingInInflater - bytesToGetFromInflater));
        System.out.println(
            "From inflaterBuf: inflaterBufHeaderStartIndex="
                + inflaterBufHeaderStartIndex
                + " bytesToGetFromInflater="
                + bytesToGetFromInflater);

        crc.update(inflaterBuf, inflaterBufHeaderStartIndex, bytesToGetFromInflater);

        loggingHack(
            "Hex bytes read from inflated compositeReadableBuffer: "
                + bytesToHex(inflaterBuf, inflaterBufHeaderStartIndex, bytesToGetFromInflater));
        inflater.reset();
        inflater.setInput(
            inflaterBuf,
            inflaterBufHeaderStartIndex + bytesToGetFromInflater,
            bytesRemainingInInflater - bytesToGetFromInflater);

        bytesToSkip -= bytesToGetFromInflater;
      }

      if (bytesToSkip > 0) {
        loggingHack("Skipping " + bytesToSkip + " from compressedData");
        byte[] buf = new byte[512];
        int total = 0;
        while (total < bytesToSkip) {
          int toRead = Math.min(bytesToSkip - total, buf.length);
          compressedData.readBytes(buf, 0, toRead);
          crc.update(buf, 0, toRead);
          total += toRead;
        }
      }

      bytesConsumed += length;
    }
  }

  /**
   * Returns true when all compressedData has been input to the inflater and the inflater is unable
   * to produce more output.
   */
  public boolean isStalled() {
    checkState(!closed, "GZipInflatingBuffer is closed");
    return compressedData.readableBytes() == 0 && (inflater.needsInput() || inflater.finished());
  }

  /**
   * Returns true when there is compressedData that has not been input to the inflater or the
   * inflater has not consumed all of its input.
   */
  public boolean hasPartialData() {
    checkState(!closed, "GZipInflatingBuffer is closed");
    return compressedData.readableBytes() != 0 || inflater.getRemaining() != 0;
  }

  /** Adds additional compressed data. */
  public void addCompressedBytes(ReadableBuffer buffer) {
    checkState(!closed, "GZipInflatingBuffer is closed");
    loggingHack("Adding " + buffer.readableBytes() + " bytes to compressedData");
    compressedData.addBuffer(buffer);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      compressedData.close();
      inflater.end();
    }
  }

  /**
   * Returns the number of compressed bytes processed since the last invocation of this method.
   *
   * <p>This does not maintain a cumulative total count to avoid overflow issues with streams
   * containing large amounts of data.
   */
  public int getAndResetCompressedBytesConsumed() {
    checkState(!closed, "GZipInflatingBuffer is closed");

    int ret = bytesConsumed;
    bytesConsumed = 0;
    return ret;
  }

  /**
   * Reads up to min(bytesToRead, MAX_BUFFER_SIZE) of uncompressed data into bufferToWrite.
   *
   * @param bytesRequested max number of bytes to decompress
   * @param bufferToWrite destination for uncompressed data
   * @return the number of bytes read into bufferToWrite
   */
  public int readUncompressedBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite)
      throws DataFormatException, ZipException {
    checkState(!closed, "GZipInflatingBuffer is closed");

    if (uncompressedBuf == null) {
      uncompressedBuf = new byte[Math.min(bytesRequested, MAX_BUFFER_SIZE)];
    }

    loggingHack("bytesRequested: " + bytesRequested);
    loggingHack("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
    loggingHack("uncompressedBufLen = " + uncompressedBuf.length);
    loggingHack("uncompressedBufWriterIndex (before): " + uncompressedBufWriterIndex);

    int bytesNeeded;
    boolean madeProgress = true;
    while (madeProgress
        && (bytesNeeded = uncompressedBuf.length - uncompressedBufWriterIndex) > 0) {
      loggingHack("State: " + state);
      loggingHack("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
      loggingHack("uncompressedBufLen = " + uncompressedBuf.length);
      switch (state) {
        case HEADER:
          madeProgress = processHeader();
          break;
        case HEADER_EXTRA_LEN:
          madeProgress = processHeaderExtraLen();
          break;
        case HEADER_EXTRA:
          madeProgress = processHeaderExtra();
          break;
        case HEADER_NAME:
          madeProgress = processHeaderName();
          break;
        case HEADER_COMMENT:
          madeProgress = processHeaderComment();
          break;
        case HEADER_CRC:
          madeProgress = processHeaderCrc();
          break;
        case INITIALIZE_INFLATER:
          madeProgress = initializeInflater();
          break;
        case INFLATING:
          madeProgress = inflate(bytesNeeded);
          break;
        case INFLATER_NEEDS_INPUT:
          madeProgress = fill();
          break;
        case TRAILER:
          madeProgress = processTrailer();
          break;
        default:
          throw new AssertionError("Invalid state: " + state);
      }
    }

    loggingHack("uncompressedBufWriterIndex (after): " + uncompressedBufWriterIndex);

    if (uncompressedBufWriterIndex > 0) {
      loggingHack("Returning with data!..." + state);
      loggingHack("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
      loggingHack(inflater.getRemaining());
      loggingHack(compressedData.readableBytes());
      int bytesToWrite = uncompressedBufWriterIndex; // uncompressedBuf.length;
      bufferToWrite.addBuffer(ReadableBuffers.wrap(uncompressedBuf, 0, bytesToWrite));
      uncompressedBufWriterIndex = 0; // reset
      uncompressedBuf = null;
      return bytesToWrite;
    } else {
      loggingHack("Giving up..." + state);
      loggingHack(inflater.getRemaining());
      loggingHack(compressedData.readableBytes());
    }

    return 0;
  }

  // We are requesting bytesToInflate.
  private boolean inflate(int bytesToInflate) throws DataFormatException, ZipException {
    loggingHack("bytesToInflate: " + bytesToInflate);
    int bytesAlreadyConsumed = inflater.getTotalIn();
    try {
      int n = inflater.inflate(uncompressedBuf, uncompressedBufWriterIndex, bytesToInflate);
      bytesConsumed += inflater.getTotalIn() - bytesAlreadyConsumed;
      crc.update(uncompressedBuf, uncompressedBufWriterIndex, n);
      uncompressedBufWriterIndex += n;

      if (inflater.finished()) {
        loggingHack("Finished! Inflater needs input: " + inflater.needsInput());
        loggingHack("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
        state = State.TRAILER;
        // Eagerly parse trailer, if possible, to detect CRC errors
        return processTrailer();
      } else if (inflater.needsInput()) {
        state = State.INFLATER_NEEDS_INPUT;
      }

      return true;
    } catch (DataFormatException e) {
      // Wrap the exception so tests can check for a specific prefix
      throw new DataFormatException("Inflater data format exception: " + e.getMessage());
    }
  }

  private boolean initializeInflater() {
    crc.reset();
    int bytesRemainingInInflater = inflater.getRemaining();
    inflater.reset();
    if (bytesRemainingInInflater > 0) {
      int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;
      inflater.setInput(inflaterBuf, inflaterBufHeaderStartIndex, bytesRemainingInInflater);
      state = State.INFLATING;
    } else {
      state = State.INFLATER_NEEDS_INPUT;
    }
    return true;
  }

  private boolean fill() {
    // When this is called, inflater buf has already been read. Safe to wipe it.
    checkState(inflater.needsInput(), "inflater already has data");
    int bytesToAdd = Math.min(compressedData.readableBytes(), INFLATE_BUFFER_SIZE);
    if (bytesToAdd > 0) {
      compressedData.readBytes(inflaterBuf, 0, bytesToAdd);
      loggingHack(
          "Raw bytes read and set as inflater input: " + bytesToHex(inflaterBuf, bytesToAdd));
      inflaterBufLen = bytesToAdd;
      inflater.setInput(inflaterBuf, 0, inflaterBufLen);
      state = State.INFLATING;
      return true;
    } else {
      return false;
    }
  }

  private boolean processHeader() throws ZipException {
    crc.reset();

    loggingHack("nextFrame.readableBytes(): " + nextFrame.readableBytes());

    if (GZIP_BASE_HEADER_SIZE > nextFrame.readableBytes()) {
      return false;
    }

    // Check header magic
    int b = readUnsignedShort(nextFrame);
    if (b != GZIP_MAGIC) {
      loggingHack("not matched: " + b +  " != " +  GZIP_MAGIC);
      throw new ZipException("Not in GZIP format");
    }

    // Check compression method
    if (nextFrame.readUnsignedByte() != 8) {
      throw new ZipException("Unsupported compression method");
    }

    // Read flags, ignore MTIME, XFL, and OS fields.
    gzipHeaderFlag = nextFrame.readUnsignedByte();
    nextFrame.skipBytes(6);

    state = State.HEADER_EXTRA_LEN;
    return true;
  }

  private boolean processHeaderExtraLen() {
    loggingHack("gzipHeaderFlag: " + gzipHeaderFlag);
    if ((gzipHeaderFlag & FEXTRA) != FEXTRA) {
      state = State.HEADER_NAME;
      return true;
    }
    if (USHORT_LEN  > nextFrame.readableBytes()) {
      return false;
    }
    headerExtraToRead = readUnsignedShort(nextFrame);
    state = State.HEADER_EXTRA;
    return true;
  }

  private boolean processHeaderExtra() {
    // TODO this is awkward
    if (nextFrame.readableBytes() < headerExtraToRead) {
      return false;
    } else {
      nextFrame.skipBytes(headerExtraToRead);
    }
    state = State.HEADER_NAME;
    return true;
  }

  private boolean processHeaderName() {
    if ((gzipHeaderFlag & FNAME) != FNAME) {
      state = State.HEADER_COMMENT;
      return true;
    }
    // TODO - this should read as much as possible at a time, then put it back if necessary?
    while (nextFrame.readableBytes() > 0) {
      if (nextFrame.readUnsignedByte() == 0) {
        state = State.HEADER_COMMENT;
        return true;
      }
    }
    return false;
  }

  private boolean processHeaderComment() {
    if ((gzipHeaderFlag & FCOMMENT) != FCOMMENT) {
      state = State.HEADER_CRC;
      return true;
    }
    // TODO utility function for HEADER NAME too
    // TODO - this should read as much as possible at a time, then put it back if necessary?
    // TODO - requires care with CRC

    while (nextFrame.readableBytes() > 0) {
      if (nextFrame.readUnsignedByte() == 0) {
        state = State.HEADER_CRC;
        return true;
      }
    }
    return false;
  }

  private boolean processHeaderCrc() throws ZipException {
    if ((gzipHeaderFlag & FHCRC) != FHCRC) {
      // TODO extract transitions into function of current state? e.g., state = nextState(State...)
      // Could also do this just for headers (separate enum)
      state = State.INITIALIZE_INFLATER;
      return true;
    }
    int desiredCrc16 = (int) crc.getValue() & 0xffff;
    if (nextFrame.readableBytes() >= USHORT_LEN) {
      loggingHack("Expecting header CRC16 of " + desiredCrc16);
      if (desiredCrc16 != readUnsignedShort(nextFrame)) {
        throw new ZipException("Corrupt GZIP header");
      }
      state = State.INITIALIZE_INFLATER;
      return true;
    }
    return false;
  }

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUnsignedShort(CrcCompositeReadableBuffer buffer) {
    loggingHack(
        "readUnsignedShort - compositeReadableBuffer.readableBytes(): " + buffer.readableBytes());
    return buffer.readUnsignedByte() | (buffer.readUnsignedByte() << 8);
  }

  /*
   * Reads unsigned integer in Intel byte order.
   */
  private long readUnsignedInt(CrcCompositeReadableBuffer buffer) {
    long s = readUnsignedShort(buffer);
    return ((long) readUnsignedShort(buffer) << 16) | s;
  }

  private boolean processTrailer() throws ZipException {
    long bytesWritten = inflater.getBytesWritten(); // save because the read may reset inflater

    loggingHack("crc.getValue() " + crc.getValue());
    long desiredCrc = crc.getValue();
    long desiredBytesWritten = (bytesWritten & 0xffffffffL);
    loggingHack("inflater.getBytesWritten() & 0xffffffffL: " + desiredBytesWritten);


    loggingHack("State..." + state);
    loggingHack("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
    loggingHack(inflater.getRemaining());
    loggingHack(compressedData.readableBytes());
    if (GZIP_TRAILER_SIZE > nextFrame.readableBytes()) {
      return false;
    }

    if ((readUnsignedInt(nextFrame) != desiredCrc)
        ||
        // rfc1952; ISIZE is the input size modulo 2^32
        (readUnsignedInt(nextFrame) != (bytesWritten & 0xffffffffL))) {
      throw new ZipException("Corrupt GZIP trailer");
    }

    state = State.HEADER;

    return true;
  }

  // TODO - remove all of this
  private boolean outputLogs = false;

  private void loggingHack(Object s) {
    if (outputLogs) {
      System.out.println(s.toString());
    }
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
  public static String bytesToHex(byte b) {
    byte[] bytes = { b };
    return bytesToHex(bytes);
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

  /** Javadoc. */
  // TODO - remove
  public static String bytesToHex(byte[] bytes, int offset, int len) {
    char[] hexChars = new char[len * 3];
    for (int j = 0; j < len; j++) {
      int v = bytes[offset + j] & 0xFF;
      hexChars[j * 3] = hexArray[v >>> 4];
      hexChars[j * 3 + 1] = hexArray[v & 0x0F];
      hexChars[j * 3 + 2] = '-';
    }
    return new String(hexChars) + " (" + len + " bytes)";
  }
}
