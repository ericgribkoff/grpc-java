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
 * <p>Some gzip parsing code adapted from java.util.zip.GZIPInputStream.
 */
@NotThreadSafe
public class GzipInflatingBuffer implements Closeable {

  private static final int INFLATE_BUFFER_SIZE = 512;
  private static final int MAX_BUFFER_SIZE = 1024 * 4;
  private static final int UNSIGNED_SHORT_SIZE = 2;

  /** Gzip header magic number. */
  public static final int GZIP_MAGIC = 0x8b1f;

  private static final int GZIP_HEADER_MIN_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  /** Gzip file header flags. (FTEXT is ignored.) */
  private static final int FHCRC = 2; // Header CRC
  private static final int FEXTRA = 4; // Extra field
  private static final int FNAME = 8; // File name
  private static final int FCOMMENT = 16; // File comment

  /**
   * Reads gzip header and trailer bytes from the inflater's buffer (if bytes beyond the inflate
   * block were given to the inflater) and then from {@code gzippedData}.
   *
   * <p>This class also updates the CRC whenever data is read. It is the responsibility of the
   * caller to verify and reset the CRC as needed, as well as caching the current CRC value when
   * necessary before reading further bytes.
   */
  private class GzipMetadataReader {

    private int readableBytes() {
      return inflater.getRemaining() + gzippedData.readableBytes();
    }

    /** Retrieves the next unsigned byte, adding it to the CRC. */
    private int readUnsignedByte() {
      int bytesRemainingInInflater = inflater.getRemaining();
      int b;
      if (bytesRemainingInInflater > 0) {
        int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;

        b = inflaterBuf[inflaterBufHeaderStartIndex] & 0xFF;

        inflater.reset();
        inflater.setInput(
            inflaterBuf, inflaterBufHeaderStartIndex + 1, bytesRemainingInInflater - 1);
      } else {
        b = gzippedData.readUnsignedByte();
      }
      crc.update(b);
      bytesConsumed += 1;
      return b;
    }

    /** Skips {@code length} bytes, adding them to the CRC. */
    private void skipBytes(int length) {
      int bytesToSkip = length;
      int bytesRemainingInInflater = inflater.getRemaining();

      if (bytesRemainingInInflater > 0) {
        int bytesToGetFromInflater = Math.min(bytesRemainingInInflater, bytesToSkip);

        int inflaterBufHeaderStartIndex = inflaterBufLen - bytesRemainingInInflater;

        crc.update(inflaterBuf, inflaterBufHeaderStartIndex, bytesToGetFromInflater);

        inflater.reset();
        inflater.setInput(
            inflaterBuf,
            inflaterBufHeaderStartIndex + bytesToGetFromInflater,
            bytesRemainingInInflater - bytesToGetFromInflater);

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
  private final Inflater inflater = new Inflater(true);
  private final byte[] inflaterBuf = new byte[INFLATE_BUFFER_SIZE];
  private int inflaterBufLen;

  private State state = State.HEADER;
  private boolean closed = false;

  /** Output buffer for inflated bytes. */
  private byte[] inflatedBuf;
  private int inflatedBufWriteIndex;

  /** Extra state variables for parsing gzip header flags. */
  private int gzipHeaderFlag;
  private int headerExtraToRead;

  /**
   * Tracks gzipped bytes consumed since last {@link #getAndResetGzippedBytesConsumed()} call.
   */
  private int bytesConsumed = 0;

  /**
   * Returns true when all of {@code gzippedData} has been input to the inflater and the inflater is
   * unable to produce more output.
   */
  public boolean isStalled() {
    checkState(!closed, "GzipInflatingBuffer is closed");
    return gzippedData.readableBytes() == 0 && inflater.needsInput();
  }

  /**
   * Returns true when there is gzippedData that has not been input to the inflater or the inflater
   * has not consumed all of its input.
   */
  public boolean hasPartialData() {
    checkState(!closed, "GzipInflatingBuffer is closed");
    return gzippedData.readableBytes() != 0 || inflater.getRemaining() != 0;
  }

  /** Adds more gzipped data. */
  public void addGzippedBytes(ReadableBuffer buffer) {
    checkState(!closed, "GzipInflatingBuffer is closed");
    gzippedData.addBuffer(buffer);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      gzippedData.close();
      inflater.end();
    }
  }

  /**
   * Returns the number of gzipped bytes processed since the last invocation of this method, and
   * resets this counter to 0.
   *
   * <p>This does report maintain a cumulative total count to avoid overflow issues with streams
   * containing large amounts of data.
   */
  public int getAndResetGzippedBytesConsumed() {
    checkState(!closed, "GzipInflatingBuffer is closed");

    int ret = bytesConsumed;
    bytesConsumed = 0;
    return ret;
  }

  /**
   * Reads up to min(bytesToRead, MAX_BUFFER_SIZE) of inflated data into bufferToWrite.
   *
   * @param bytesRequested max number of bytes to inflate
   * @param bufferToWrite destination for inflated data
   * @return the number of bytes written into bufferToWrite
   */
  public int inflateBytes(int bytesRequested, CompositeReadableBuffer bufferToWrite)
      throws DataFormatException, ZipException {
    checkState(!closed, "GzipInflatingBuffer is closed");

    if (inflatedBuf == null) {
      inflatedBuf = new byte[Math.min(bytesRequested, MAX_BUFFER_SIZE)];
    }

    int bytesNeeded;
    boolean madeProgress = true;
    while (madeProgress
        && (bytesNeeded = inflatedBuf.length - inflatedBufWriteIndex) > 0) {
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


    if (inflatedBufWriteIndex > 0) {
      int bytesToWrite = inflatedBufWriteIndex; // inflatedBuf.length;
      bufferToWrite.addBuffer(ReadableBuffers.wrap(inflatedBuf, 0, bytesToWrite));
      inflatedBufWriteIndex = 0; // reset
      inflatedBuf = null;
      return bytesToWrite;
    }

    return 0;
  }

  // We are requesting bytesToInflate.
  private boolean inflate(int bytesToInflate) throws DataFormatException, ZipException {
    int bytesAlreadyConsumed = inflater.getTotalIn();
    try {
      int n = inflater.inflate(inflatedBuf, inflatedBufWriteIndex, bytesToInflate);
      bytesConsumed += inflater.getTotalIn() - bytesAlreadyConsumed;
      crc.update(inflatedBuf, inflatedBufWriteIndex, n);
      inflatedBufWriteIndex += n;

      if (inflater.finished()) {
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
    int bytesToAdd = Math.min(gzippedData.readableBytes(), INFLATE_BUFFER_SIZE);
    if (bytesToAdd > 0) {
      gzippedData.readBytes(inflaterBuf, 0, bytesToAdd);
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


    if (GZIP_HEADER_MIN_SIZE > gzipMetadataReader.readableBytes()) {
      return false;
    }

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
    }
    if (UNSIGNED_SHORT_SIZE > gzipMetadataReader.readableBytes()) {
      return false;
    }
    headerExtraToRead = readUnsignedShort(gzipMetadataReader);
    state = State.HEADER_EXTRA;
    return true;
  }

  private boolean processHeaderExtra() {
    // TODO this is awkward
    if (gzipMetadataReader.readableBytes() < headerExtraToRead) {
      return false;
    } else {
      gzipMetadataReader.skipBytes(headerExtraToRead);
    }
    state = State.HEADER_NAME;
    return true;
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
      // TODO extract transitions into function of current state? e.g., state = nextState(State...)
      // Could also do this just for headers (separate enum)
      state = State.INITIALIZE_INFLATER;
      return true;
    }
    int desiredCrc16 = (int) crc.getValue() & 0xffff;
    if (gzipMetadataReader.readableBytes() >= UNSIGNED_SHORT_SIZE) {
      if (desiredCrc16 != readUnsignedShort(gzipMetadataReader)) {
        throw new ZipException("Corrupt GZIP header");
      }
      state = State.INITIALIZE_INFLATER;
      return true;
    }
    return false;
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

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUnsignedShort(GzipMetadataReader buffer) {
    return buffer.readUnsignedByte() | (buffer.readUnsignedByte() << 8);
  }

  /*
   * Reads unsigned integer in Intel byte order.
   */
  private long readUnsignedInt(GzipMetadataReader buffer) {
    long s = readUnsignedShort(buffer);
    return ((long) readUnsignedShort(buffer) << 16) | s;
  }

  private boolean processTrailer() throws ZipException {
    long bytesWritten = inflater.getBytesWritten(); // save because the read may reset inflater

    if (GZIP_TRAILER_SIZE > gzipMetadataReader.readableBytes()) {
      return false;
    }

    if (crc.getValue() != (readUnsignedInt(gzipMetadataReader))
        ||
        // rfc1952; ISIZE is the input size modulo 2^32
        (readUnsignedInt(gzipMetadataReader) != (bytesWritten & 0xffffffffL))) {
      throw new ZipException("Corrupt GZIP trailer");
    }

    state = State.HEADER;

    return true;
  }
}
