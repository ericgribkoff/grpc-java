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

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;
import javax.annotation.concurrent.NotThreadSafe;
import javax.xml.crypto.Data;

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

  private enum State {
    HEADER,
    HEADER_EXTRA_LEN,
    HEADER_EXTRA,
    HEADER_NAME,
    HEADER_COMMENT,
    HEADER_CRC,
    INFLATING,
    INFLATER_NEEDS_INPUT,
    TRAILER
  }

  private static final int INFLATE_BUFFER_SIZE = 512;
  private static final int MAX_BUFFER_SIZE = 1024 * 4;

  private byte[] inflaterBuf = new byte[INFLATE_BUFFER_SIZE];
  private int inflaterBufLen;

  private byte[] uncompressedBuf;
  private int uncompressedBufWriterIndex;

  private int headerExtraToRead;
  private int gzipHeaderFlag;

  private static final int USHORT_LEN = 2;

  /** GZIP header magic number. */
  public static final int GZIP_MAGIC = 0x8b1f;

  /*
   * File header flags. (FTEXT intentionally omitted)
   */
  private static final int FHCRC = 2; // Header CRC
  private static final int FEXTRA = 4; // Extra field
  private static final int FNAME = 8; // File name
  private static final int FCOMMENT = 16; // File comment

  /** CRC-32 for uncompressed data. */
  protected CRC32 crc = new CRC32();

  private byte[] tmpBuffer = new byte[128]; // for skipping/parsing(?) header, trailer data

  public boolean isStalled() {
    // TODO - not quite right, but want to verify finish state.
    // TODO state != State.INFLATING is not sufficient if we are eagerly parsing TRAILERS...
    // but inflater.getRemaining() may have junk data and we are stalled.
    return compressedData.readableBytes() == 0 && state != State.INFLATING;
  }

  public boolean hasPartialData() {
    return compressedData.readableBytes() != 0 || inflater.getRemaining() != 0;
  }

  public void addCompressedBytes(ReadableBuffer buffer) {
    System.out.println("Adding " + buffer.readableBytes() + " bytes to compressedData");
    compressedData.addBuffer(buffer);
  }

  // TODO Need some kind of close method here
  public void close() {

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
   * <p>This may write MORE than bytesRequested into bufferToWrite if a previous call requested more
   * bytes, which were not fully available, and a subsequent call requests fewer bytes than
   * previously prepared.
   *
   * @param bytesRequested
   * @param bufferToWrite
   * @return the number of bytes read into bufferToWrite
   */
  public int readUncompressedBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite) throws DataFormatException, ZipException {
    if (uncompressedBuf == null) {
      uncompressedBuf = new byte[Math.min(bytesRequested, MAX_BUFFER_SIZE)];
    }

    System.out.println("bytesRequested: " + bytesRequested);
    System.out.println("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
    System.out.println("uncompressedBufLen = " + uncompressedBuf.length);
    System.out.println("uncompressedBufWriterIndex (before): " + uncompressedBufWriterIndex);

    int bytesNeeded;
    // TODO - better stopping condition for this loop (embedded returns are confusing)
    while ((bytesNeeded = uncompressedBuf.length - uncompressedBufWriterIndex) > 0) {
      System.out.println("State: " + state);
      // TODO - could also see if new state != old state to measure progress...
      boolean madeProgress;
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
        case INFLATING:
          // Pass the body bytes to the inflater
          inflate(bytesNeeded);
          // inflate will either produce needed bytes or transition to another state
          madeProgress = true;
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

      if (!madeProgress) {
        System.out.println("Returning...");
        System.out.println("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
        System.out.println(inflater.getRemaining());
        System.out.println(compressedData.readableBytes());
        return 0;
      }
    }

    System.out.println("uncompressedBufWriterIndex (after): " + uncompressedBufWriterIndex);

    if (uncompressedBufWriterIndex == uncompressedBuf.length) {
      // TODO fix this - we just want to eagerly parse the trailer when available, but if we are
      // requesting *exactly* the number of bytes available, we won't otherwise parse the trailer.
      //      if (inflater.finished()) {
      //        state = State.TRAILER;
      //        processTrailer();
      //      }
      System.out.println("Returning with data!...");
      System.out.println("uncompressedBufWriterIndex: " + uncompressedBufWriterIndex);
      System.out.println(inflater.getRemaining());
      System.out.println(compressedData.readableBytes());
      int bytesToWrite = uncompressedBuf.length;
//      System.out.println("All " + bytesToUncompress + " bytes inflated: " +
//              bytesToHex(uncompressedBuf, uncompressedBufWriterIndex));
      bufferToWrite.addBuffer(ReadableBuffers.wrap(uncompressedBuf, 0, bytesToWrite));
      uncompressedBufWriterIndex = 0; // reset
      uncompressedBuf = null;
      return bytesToWrite;
    }

    return 0;
  }

  // We are requesting bytesToInflate.
  private void inflate(int bytesToInflate) throws DataFormatException {
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
          throw new AssertionError("inflater produced no output");
        }
      } else {
        System.out.println("pre: crc.getValue() " + crc.getValue());
        crc.update(uncompressedBuf, uncompressedBufWriterIndex, n);
        System.out.println("post: crc.getValue() " + crc.getValue());
        uncompressedBufWriterIndex += n;
        //        System.out.println("INFLATED (" + n + " bytes = " + uncompressedBufWriterIndex
        //                + " total) " +
        //                bytesToHex(uncompressedBuf, uncompressedBufWriterIndex));
      }
    } catch (DataFormatException e) {
      // TODO properly abort when this happens
      System.out.println("DataFormatException");
      e.printStackTrace(System.out);
      throw new DataFormatException("Inflater data format exception: " + e.getMessage());
    }
    return;
  }

  private boolean fill() {
    int bytesToAdd = Math.min(compressedData.readableBytes(), INFLATE_BUFFER_SIZE);
    if (bytesToAdd > 0) {
      compressedData.readBytes(inflaterBuf, 0, bytesToAdd);
      //      System.out.println("Raw bytes read and set as inflater input: " +
      //              bytesToHex(inflaterBuf, bytesToAdd));
      inflaterBufLen = bytesToAdd;
      inflater.setInput(inflaterBuf, 0, inflaterBufLen);
      state = State.INFLATING;
      return true;
    } else {
      return false;
    }
  }

  // TODO - reduce copying :(
  private boolean readBytesFromInflaterBufOrCompressedData(int n, byte[] returnBuffer) {
    int bytesRemainingInInflater = inflater.getRemaining();
    int compressedDataReadableByes = compressedData.readableBytes();

    if (bytesRemainingInInflater + compressedDataReadableByes < n) {
      return false;
    }

    int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;
    int bytesToGetFromInflater = Math.min(n, bytesRemainingInInflater);

    if (bytesToGetFromInflater > 0) {
      System.out.println("bytesToGetFromInflater: " + bytesToGetFromInflater);
      System.out.println("Setting inflater input: start="
          + (inflaterBufHeaderStartIndex + bytesToGetFromInflater) + " len=" +
          (bytesRemainingInInflater - bytesToGetFromInflater));
      // Can't reset here when this is invoked from trailer - we'll need inflater's # bytes written
      // later...well, we can just save it first.
      inflater.reset();
      inflater.setInput(
          inflaterBuf,
          inflaterBufHeaderStartIndex + bytesToGetFromInflater,
          bytesRemainingInInflater - bytesToGetFromInflater);
    }

    for (int i = 0; i < bytesToGetFromInflater; i++) {
      returnBuffer[i] = inflaterBuf[inflaterBufHeaderStartIndex + i];
    }

    int bytesToGetFromCompressedData = n - bytesToGetFromInflater;
    System.out.println("bytesToGetFromCompressedData: " + bytesToGetFromCompressedData);
    compressedData.readBytes(returnBuffer, bytesToGetFromInflater, bytesToGetFromCompressedData);

    bytesConsumed += n;

    return true;
  }

  private boolean processHeader() throws ZipException {
    if (!readBytesFromInflaterBufOrCompressedData(GZIP_BASE_HEADER_SIZE, tmpBuffer)) {
      return false;
    }

    // TODO - isn't this broken with extra flags? We need to reset after we've consumed additional
    // header data out of buffer too...
    // Not to mention we may have updated the inflater's buffer in
    // readBytesFromInflaterBufOrCompressedData...
//    inflater.reset(); // TODO - make this more obviously required here!

    // TODO - handle header CRC
    crc.reset();

    crc.update(tmpBuffer, 0, GZIP_BASE_HEADER_SIZE);

    // Check header magic
    if (readUnsignedShort(tmpBuffer[0], tmpBuffer[1]) != GZIP_MAGIC) {
      System.out.println(readUnsignedShort(tmpBuffer[0], tmpBuffer[1]));
      System.out.println(readUnsignedByte(tmpBuffer[0]));
      System.out.println(readUnsignedByte(tmpBuffer[1]));
      System.out.println("not matched");
      throw new ZipException("Not in GZIP format");
    }

    // Check compression method
    if (readUnsignedByte(tmpBuffer[2]) != 8) {
      throw new ZipException("Unsupported compression method");
    }

    // Read flags, ignore MTIME, XFL, and OS fields.
    gzipHeaderFlag = readUnsignedByte(tmpBuffer[3]);

    state = State.HEADER_EXTRA_LEN;
    return true;
  }


  private boolean processHeaderExtraLen() {
    // TODO - check flag directly here :-)
    System.out.println("gzipHeaderFlag: " + gzipHeaderFlag);
    if ((gzipHeaderFlag & FEXTRA) != FEXTRA) {
      // TODO extract transitions into function of current state? e.g., state = nextState(State...)
      // Could also do this just for headers (separate enum)
      state = State.HEADER_NAME;
      return true;
    }
    if (!readBytesFromInflaterBufOrCompressedData(USHORT_LEN, tmpBuffer)) {
      return false;
    }
    crc.update(tmpBuffer, 0, USHORT_LEN);
    headerExtraToRead = readUnsignedShort(tmpBuffer[0], tmpBuffer[1]);
    state = State.HEADER_EXTRA;
    return true;
  }

  private boolean processHeaderExtra() {
//    int bytesAvailable = readableBytesFromInflaterBufOrCompressedData();
    while (headerExtraToRead > 0) {
      int bytesToRead = Math.min(headerExtraToRead, tmpBuffer.length);
      if (!readBytesFromInflaterBufOrCompressedData(bytesToRead, tmpBuffer)) {
        return false;
      } else {
        headerExtraToRead -= bytesToRead;
        crc.update(tmpBuffer, 0, bytesToRead);
      }
    }
    state = State.HEADER_NAME;
    return true;
  }

  private boolean processHeaderName() {
    if ((gzipHeaderFlag & FNAME) != FNAME) {
      // TODO extract transitions into function of current state? e.g., state = nextState(State...)
      // Could also do this just for headers (separate enum)
      state = State.HEADER_COMMENT;
      return true;
    }
    while (readBytesFromInflaterBufOrCompressedData(1, tmpBuffer)) {
      crc.update(tmpBuffer[0]);
      System.out.println(tmpBuffer[0]);
      if (readUnsignedByte(tmpBuffer[0]) == 0) {
        state = State.HEADER_COMMENT;
        return true;
      }
    }
    return false;
  }

  private boolean processHeaderComment() {
    if ((gzipHeaderFlag & FCOMMENT) != FCOMMENT) {
      // TODO extract transitions into function of current state? e.g., state = nextState(State...)
      // Could also do this just for headers (separate enum)
      state = State.HEADER_CRC;
      return true;
    }
    while (readBytesFromInflaterBufOrCompressedData(1, tmpBuffer)) {
      crc.update(tmpBuffer[0]);
      if (readUnsignedByte(tmpBuffer[0]) == 0) {
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
      // TODO Definitely a good idea due to need to do things like crc.reset(), inflater.reset()
      crc.reset();
      state = State.INFLATING;
      return true;
    }
    if (readBytesFromInflaterBufOrCompressedData(USHORT_LEN, tmpBuffer)) {
      int v = (int) crc.getValue() & 0xffff;
      System.out.println("Expecting header CRC16 of " + v);
      if (v != readUnsignedShort(tmpBuffer[0], tmpBuffer[1])) {
        throw new ZipException("Corrupt GZIP header");
      }
      crc.reset();
      state = State.INFLATING;
      return true;
    }
    return false;
  }

  // TODO - throw exception if out of range??
  private int readUnsignedByte(byte b) {
    return b & 0xFF;
  }

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUnsignedShort(byte b1, byte b2) {
    return (readUnsignedByte(b2) << 8) | readUnsignedByte(b1);
  }

  /*
   * Reads unsigned integer in Intel byte order.
   */
  private long readUnsignedInt(byte b1, byte b2, byte b3, byte b4) {
    long s = readUnsignedShort(b1, b2);
    return ((long) readUnsignedShort(b3, b4) << 16) | s;
  }


  private boolean processTrailer() throws ZipException {
    long bytesWritten = inflater.getBytesWritten(); // save because the read may reset inflater

    if (!readBytesFromInflaterBufOrCompressedData(GZIP_TRAILER_SIZE, tmpBuffer)) {
      return false;
    }

//    try {
      System.out.println("unsigned int that should be checksum: "
          + readUnsignedInt(tmpBuffer[0], tmpBuffer[1], tmpBuffer[2],   tmpBuffer[3]));
      System.out.println("crc.getValue() " + crc.getValue());
      long desiredCrc = crc.getValue();
      System.out.println("as byte array: " + Arrays.toString(Longs.toByteArray(desiredCrc)));
      System.out.println("-13 as unsigned int: " + readUnsignedByte((byte) -13));
      long desiredBytesWritten  = (bytesWritten & 0xffffffffL);
      System.out.println("inflater.getBytesWritten() & 0xffffffffL: "
          + desiredBytesWritten);
      System.out.println("as byte array: " + Arrays.toString(Longs.toByteArray(desiredBytesWritten)));
      System.out.println("what should be ^: "
          + readUnsignedInt(tmpBuffer[4], tmpBuffer[5], tmpBuffer[6], tmpBuffer[7]));

      if ((readUnsignedInt(tmpBuffer[0], tmpBuffer[1], tmpBuffer[2], tmpBuffer[3])
              != crc.getValue())
          ||
          // rfc1952; ISIZE is the input size modulo 2^32
          (readUnsignedInt(tmpBuffer[4], tmpBuffer[5], tmpBuffer[6], tmpBuffer[7])
              != (bytesWritten & 0xffffffffL))) {
        throw new ZipException("Corrupt GZIP trailer");
      }
//    }
//    catch (IOException e) {
//      System.out.println("IOException on trailer");
//      throw new RuntimeException(e);
//    }

    state = State.HEADER;

    return true;
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
