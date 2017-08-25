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
 * Processes gzip streams, delegating to {@link Inflater} to perform on-demand inflation of the
 * deflated blocks. Like {@link java.util.zip.GZIPInputStream}, this handles concatenated gzip
 * streams. Unlike {@link java.util.zip.GZIPInputStream}, this allows for incremental processing of
 * gzip streams, allowing data to be inflated as it arrives over the wire.
 *
 * <p>This also frees the inflate context when the end of a gzip stream is reached without another
 * concatenated stream available to inflate.
 *
 * <p>The gzip parsing code is adapted from java.util.zip.GZIPInputStream.
 */
@NotThreadSafe
class GzipInflatingBuffer implements Closeable {

  private static final int INFLATE_BUFFER_SIZE = 512;
  private static final int MAX_OUTPUT_BUFFER_SIZE = 1024 * 4;
  private static final int UNSIGNED_SHORT_SIZE = 2;

  /** Gzip header magic number. */
  private static final int GZIP_MAGIC = 0x8b1f;

  private static final int GZIP_HEADER_MIN_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  /** Gzip file header flags. (FTEXT is ignored.) */
  private static final int FHCRC = 2; // Header CRC

  private static final int FEXTRA = 4; // Extra field
  private static final int FNAME = 8; // File name
  private static final int FCOMMENT = 16; // File comment

  /**
   * Reads gzip header and trailer bytes from the inflater's buffer (if bytes beyond the inflate
   * block were given to the inflater) and then from {@code gzippedData}, and handles updating the
   * CRC and the count of gzipped bytes consumed.
   */
  private class GzipMetadataReader {

    /**
     * Returns the next unsigned byte, adding it the CRC and incrementing {@code bytesConsumed}.
     *
     * <p>It is the responsibility of the caller to verify and reset the CRC as needed, as well as
     * caching the current CRC value when necessary before invoking this method.
     */
    private int readUnsignedByte() {
      int bytesRemainingInInflater = inflaterInputEnd - inflaterInputStart;
      int b;
      if (bytesRemainingInInflater > 0) {
        b = inflaterInput[inflaterInputStart] & 0xFF;
        if (inflater != null) {
          inflater.reset();
          inflater.setInput(inflaterInput, inflaterInputStart + 1, bytesRemainingInInflater - 1);
        }
        inflaterInputStart += 1;
      } else {
        b = gzippedData.readUnsignedByte();
      }
      crc.update(b);
      bytesConsumed += 1;
      return b;
    }

    /**
     * Skips {@code length} bytes, adding them to the CRC and adding {@code length} to {@code
     * bytesConsumed}.
     *
     * <p>It is the responsibility of the caller to verify and reset the CRC as needed, as well as
     * caching the current CRC value when necessary before invoking this method.
     */
    private void skipBytes(int length) {
      int bytesToSkip = length;
      int bytesRemainingInInflater = inflaterInputEnd - inflaterInputStart;

      if (bytesRemainingInInflater > 0) {
        int bytesToGetFromInflater = Math.min(bytesRemainingInInflater, bytesToSkip);
        crc.update(inflaterInput, inflaterInputStart, bytesToGetFromInflater);
        if (inflater != null) {
          inflater.reset();
          inflater.setInput(
              inflaterInput,
              inflaterInputStart + bytesToGetFromInflater,
              bytesRemainingInInflater - bytesToGetFromInflater);
        }
        inflaterInputStart += bytesToGetFromInflater;
        bytesToSkip -= bytesToGetFromInflater;
      }

      if (bytesToSkip > 0) {
        byte[] buf = new byte[512];
        int total = 0;
        while (total < bytesToSkip) {
          int toRead = Math.min(bytesToSkip - total, buf.length);
          gzippedData.readBytes(buf, 0, toRead);
          crc.update(buf, 0, toRead);
          total += toRead;
        }
      }

      bytesConsumed += length;
    }

    private int readableBytes() {
      return (inflaterInputEnd - inflaterInputStart) + gzippedData.readableBytes();
    }
  }

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

  /**
   * This buffer holds all input gzipped data, consisting of blocks of deflated data and the
   * surrounding gzip headers and trailers. All access to the Gzip headers and trailers must be made
   * via {@link GzipMetadataReader}.
   */
  private final CompositeReadableBuffer gzippedData = new CompositeReadableBuffer();

  /** CRC-32 for gzip header and inflated data. */
  private final CRC32 crc = new CRC32();

  private final GzipMetadataReader gzipMetadataReader = new GzipMetadataReader();
  private final byte[] inflaterInput = new byte[INFLATE_BUFFER_SIZE];
  private int inflaterInputStart;
  private int inflaterInputEnd;
  private Inflater inflater;

  private State state = State.HEADER;
  private boolean closed = false;

  /** Output buffer for inflated bytes. */
  private byte[] inflaterOutput;

  private int inflaterOutputEnd;

  /** Extra state variables for parsing gzip header flags. */
  private int gzipHeaderFlag;

  private int headerExtraToRead;

  /* Number of inflated bytes per gzip stream, used to validate the gzip trailer. */
  private long expectedGzipTrailerIsize;

  /** Tracks gzipped bytes consumed during each {@link #inflateBytes} call. */
  private int bytesConsumed = 0;

  /**
   * Returns true when all of {@code gzippedData} has been input to the inflater and the inflater is
   * unable to produce more output.
   */
  boolean isStalled() {
    checkState(!closed, "GzipInflatingBuffer is closed");
    return gzippedData.readableBytes() == 0 && (inflaterInputStart == inflaterInputEnd);
  }

  /**
   * Returns true when there is gzippedData that has not been input to the inflater or the inflater
   * has not consumed all of its input.
   */
  boolean hasPartialData() {
    checkState(!closed, "GzipInflatingBuffer is closed");
    return gzippedData.readableBytes() != 0 || (inflaterInputStart < inflaterInputEnd);
  }

  /**
   * Adds more gzipped data, which will be consumed only when needed to fulfill requests made via
   * {@link #inflateBytes}.
   */
  void addGzippedBytes(ReadableBuffer buffer) {
    checkState(!closed, "GzipInflatingBuffer is closed");
    gzippedData.addBuffer(buffer);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      gzippedData.close();
      if (inflater != null) {
        inflater.end();
        inflater = null;
      }
    }
  }

  /**
   * Attempts to inflate up to {@code bytesRequested} bytes of data into {@code bufferToWrite}. This
   * method will always write as many inflated bytes as it can produce, up to a maximum of {@code
   * bytesRequested}.
   *
   * <p>This method may consume gzipped bytes without writing any data to {@code bufferToWrite}, and
   * may also write data to {@code bufferToWrite} without consuming additional gzipped bytes (if the
   * inflater on an earlier call consumed the bytes necessary to produce output).
   *
   * @param bytesRequested max number of bytes to inflate
   * @param bufferToWrite destination for inflated data
   * @return gzipped bytes consumed by the call (NOT the number of inflated bytes written)
   */
  int inflateBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite)
      throws DataFormatException, ZipException {
    checkState(!closed, "GzipInflatingBuffer is closed");

    int bytesNeeded = bytesRequested;
    while (bytesNeeded > 0) {
      int bytesWritten = fillInflatedBuf(bytesNeeded);
      if (bytesWritten == 0) {
        break;
      } else {
        bytesNeeded -= bytesWritten;
        writeInflatedBufToOutputBuffer(bufferToWrite);
      }
    }
    int savedBytesConsumed = bytesConsumed;
    bytesConsumed = 0;
    return savedBytesConsumed;
  }

  private int fillInflatedBuf(int bytesRequested) throws DataFormatException, ZipException {
    if (inflaterOutput == null) {
      inflaterOutput = new byte[Math.min(bytesRequested, MAX_OUTPUT_BUFFER_SIZE)];
    }

    int bytesNeeded;
    boolean madeProgress = true;
    while (madeProgress && (bytesNeeded = inflaterOutput.length - inflaterOutputEnd) > 0) {
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
    return inflaterOutputEnd;
  }

  private void writeInflatedBufToOutputBuffer(CompositeReadableBuffer bufferToWrite) {
    bufferToWrite.addBuffer(ReadableBuffers.wrap(inflaterOutput, 0, inflaterOutputEnd));
    inflaterOutputEnd = 0;
    inflaterOutput = null;
  }

  private boolean inflate(int bytesToInflate) throws DataFormatException, ZipException {
    checkState(inflater != null, "inflater is null");

    try {
      int inflaterTotalIn = inflater.getTotalIn();
      int n = inflater.inflate(inflaterOutput, inflaterOutputEnd, bytesToInflate);
      int bytesConsumedDelta = inflater.getTotalIn() - inflaterTotalIn;
      bytesConsumed += bytesConsumedDelta;
      inflaterInputStart += bytesConsumedDelta;
      crc.update(inflaterOutput, inflaterOutputEnd, n);
      inflaterOutputEnd += n;

      if (inflater.finished()) {
        // Save bytes written to check against the trailer ISIZE
        expectedGzipTrailerIsize = (inflater.getBytesWritten() & 0xffffffffL);
        if (gzipMetadataReader.readableBytes() <= GZIP_HEADER_MIN_SIZE + GZIP_TRAILER_SIZE) {
          // We don't have enough bytes to begin inflating a concatenated gzip stream, drop context
          inflater.end();
          inflater = null;
        }
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
    if (inflater == null) {
      inflater = new Inflater(true);
    } else {
      inflater.reset();
    }
    crc.reset();
    int bytesRemainingInInflaterInput = inflaterInputEnd - inflaterInputStart;
    if (bytesRemainingInInflaterInput > 0) {
      inflater.setInput(inflaterInput, inflaterInputStart, bytesRemainingInInflaterInput);
      state = State.INFLATING;
    } else {
      state = State.INFLATER_NEEDS_INPUT;
    }
    return true;
  }

  private boolean fill() {
    checkState(inflater != null, "inflater is null");
    checkState(inflaterInputStart == inflaterInputEnd, "inflaterInput has unconsumed bytes");
    int bytesToAdd = Math.min(gzippedData.readableBytes(), INFLATE_BUFFER_SIZE);
    if (bytesToAdd > 0) {
      inflaterInputStart = 0;
      inflaterInputEnd = bytesToAdd;
      gzippedData.readBytes(inflaterInput, inflaterInputStart, bytesToAdd);
      inflater.setInput(inflaterInput, inflaterInputStart, bytesToAdd);
      state = State.INFLATING;
      return true;
    } else {
      return false;
    }
  }

  private boolean processHeader() throws ZipException {
    if (GZIP_HEADER_MIN_SIZE > gzipMetadataReader.readableBytes()) {
      return false;
    }

    crc.reset();
    // Check header magic
    if (readUnsignedShort(gzipMetadataReader) != GZIP_MAGIC) {
      throw new ZipException("Not in GZIP format");
    }
    // Check compression method
    if (gzipMetadataReader.readUnsignedByte() != 8) {
      throw new ZipException("Unsupported compression method");
    }
    // Read flags, ignore MTIME, XFL, and OS fields.
    gzipHeaderFlag = gzipMetadataReader.readUnsignedByte();
    gzipMetadataReader.skipBytes(6 /* remaining header bytes */);

    state = State.HEADER_EXTRA_LEN;
    return true;
  }

  private boolean processHeaderExtraLen() {
    if ((gzipHeaderFlag & FEXTRA) != FEXTRA) {
      state = State.HEADER_NAME;
      return true;
    } else if (gzipMetadataReader.readableBytes() >= UNSIGNED_SHORT_SIZE) {
      headerExtraToRead = readUnsignedShort(gzipMetadataReader);
      state = State.HEADER_EXTRA;
      return true;
    } else {
      return false;
    }
  }

  private boolean processHeaderExtra() {
    if (gzipMetadataReader.readableBytes() >= headerExtraToRead) {
      gzipMetadataReader.skipBytes(headerExtraToRead);
      state = State.HEADER_NAME;
      return true;
    } else {
      return false;
    }
  }

  private boolean processHeaderName() {
    if ((gzipHeaderFlag & FNAME) != FNAME) {
      state = State.HEADER_COMMENT;
      return true;
    } else if (readBytesUntilZero()) {
      state = State.HEADER_COMMENT;
      return true;
    } else {
      return false;
    }
  }

  private boolean processHeaderComment() {
    if ((gzipHeaderFlag & FCOMMENT) != FCOMMENT) {
      state = State.HEADER_CRC;
      return true;
    } else if (readBytesUntilZero()) {
      state = State.HEADER_CRC;
      return true;
    } else {
      return false;
    }
  }

  private boolean processHeaderCrc() throws ZipException {
    if ((gzipHeaderFlag & FHCRC) != FHCRC) {
      state = State.INITIALIZE_INFLATER;
      return true;
    } else if (gzipMetadataReader.readableBytes() >= UNSIGNED_SHORT_SIZE) {
      int desiredCrc16 = (int) crc.getValue() & 0xffff;
      if (desiredCrc16 != readUnsignedShort(gzipMetadataReader)) {
        throw new ZipException("Corrupt GZIP header");
      }
      state = State.INITIALIZE_INFLATER;
      return true;
    } else {
      return false;
    }
  }

  private boolean processTrailer() throws ZipException {
    if (GZIP_TRAILER_SIZE > gzipMetadataReader.readableBytes()) {
      return false;
    }

    if (crc.getValue() != (readUnsignedInt(gzipMetadataReader))
        ||
        // rfc1952; ISIZE is the input size modulo 2^32
        (readUnsignedInt(gzipMetadataReader) != expectedGzipTrailerIsize)) {
      throw new ZipException("Corrupt GZIP trailer");
    }
    state = State.HEADER;
    return true;
  }

  /** Skip over a zero-terminated byte sequence. Returns true when the zero byte is read. */
  private boolean readBytesUntilZero() {
    while (gzipMetadataReader.readableBytes() > 0) {
      if (gzipMetadataReader.readUnsignedByte() == 0) {
        return true;
      }
    }
    return false;
  }

  /** Reads unsigned short in Intel byte order. */
  private int readUnsignedShort(GzipMetadataReader buffer) {
    return buffer.readUnsignedByte() | (buffer.readUnsignedByte() << 8);
  }

  /** Reads unsigned integer in Intel byte order. */
  private long readUnsignedInt(GzipMetadataReader buffer) {
    long s = readUnsignedShort(buffer);
    return ((long) readUnsignedShort(buffer) << 16) | s;
  }
}
