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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GzipInflatingBuffer}. */
@RunWith(JUnit4.class)
public class GzipInflatingBufferTest {
  private static final String UNCOMPRESSABLE_FILE = "/io/grpc/internal/uncompressable.bin";

  private static final int GZIP_HEADER_MIN_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;
  private static final int GZIP_HEADER_FLAG_INDEX = 3;

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

  private static final int TRUNCATED_DATA_SIZE = 10;

  private byte[] originalData;
  private byte[] gzippedData;
  private byte[] gzipHeader;
  private byte[] deflatedBytes;
  private byte[] gzipTrailer;
  private byte[] truncatedData;
  private byte[] gzippedTruncatedData;

  private GzipInflatingBuffer gzipBuffer;
  private CompositeReadableBuffer outputBuffer;

  @Before
  public void setUp() {
    gzipBuffer = new GzipInflatingBuffer();
    outputBuffer = new CompositeReadableBuffer();
    try {
      // TODO: see if asStream works without intellij
      // InputStream inputStream =
      // getClass().getResourceAsStream(UNCOMPRESSABLE_FILE);
      InputStream inputStream =
          new BufferedInputStream(
              new FileInputStream(
                  "/usr/local/google/home/ericgribkoff/github/ericgribkoff/grpc-java/core/src/"
                      + "test/resources/io/grpc/internal/uncompressable.bin"));

      ByteArrayOutputStream originalDataOutputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream gzippedOutputStream = new ByteArrayOutputStream();
      OutputStream gzippingOutputStream = new GZIPOutputStream(gzippedOutputStream);

      byte[] buffer = new byte[512];
      int n;
      while ((n = inputStream.read(buffer)) > 0) {
        originalDataOutputStream.write(buffer, 0, n);
        gzippingOutputStream.write(buffer, 0, n);
      }
      gzippingOutputStream.close();
      gzippedData = gzippedOutputStream.toByteArray();
      originalData = originalDataOutputStream.toByteArray();
      gzipHeader = Arrays.copyOf(gzippedData, GZIP_HEADER_MIN_SIZE);
      deflatedBytes =
          Arrays.copyOfRange(
              gzippedData, GZIP_HEADER_MIN_SIZE, gzippedData.length - GZIP_TRAILER_SIZE);
      gzipTrailer =
          Arrays.copyOfRange(
              gzippedData, gzippedData.length - GZIP_TRAILER_SIZE, gzippedData.length);

      truncatedData = Arrays.copyOf(originalData, TRUNCATED_DATA_SIZE);
      ByteArrayOutputStream truncatedGzippedOutputStream = new ByteArrayOutputStream();
      OutputStream smallerGzipCompressingStream =
          new GZIPOutputStream(truncatedGzippedOutputStream);
      smallerGzipCompressingStream.write(truncatedData);
      smallerGzipCompressingStream.close();
      gzippedTruncatedData = truncatedGzippedOutputStream.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up compressed data", e);
    }
  }

  @After
  public void tearDown() {
    gzipBuffer.close();
    outputBuffer.close();
  }

  @Test
  public void gzipInflateWorks() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void concatenatedStreamsWorks() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertTrue(readBytesIfPossible(truncatedData.length, outputBuffer));
    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertTrue(readBytesIfPossible(truncatedData.length, outputBuffer));

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));

    byteBuf = new byte[truncatedData.length];
    outputBuffer.readBytes(byteBuf, 0, truncatedData.length);
    assertTrue("inflated data does not match original", Arrays.equals(truncatedData, byteBuf));

    byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));

    byteBuf = new byte[truncatedData.length];
    outputBuffer.readBytes(byteBuf, 0, truncatedData.length);
    assertTrue("inflated data does not match original", Arrays.equals(truncatedData, byteBuf));
  }

  @Test
  public void readUncompressedBytesWithLargerRequest_doesNotOverflow() throws Exception {
    gzipBuffer.inflateBytes(1, outputBuffer);
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
  }

  @Test
  public void closeStopsDecompression() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertTrue(readBytesIfPossible(1, outputBuffer));
    gzipBuffer.close();
    try {
      gzipBuffer.inflateBytes(1, outputBuffer);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expectedException) {
      assertEquals("GzipInflatingBuffer is closed", expectedException.getMessage());
    }
  }

  @Test
  public void isStalledReturnsTrueAtEndOfStream() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    int bytesToWithhold = 10;
    assertTrue(readBytesIfPossible(originalData.length - bytesToWithhold, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
    assertTrue(readBytesIfPossible(bytesToWithhold, outputBuffer));
    assertTrue("gzipBuffer is not stalled", gzipBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseBetweenStreams() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertTrue("gzipBuffer is not stalled", gzipBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseBetweenSmallStreams() throws Exception {
    // Use small streams to make sure that they all fit in the inflater buffer
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));

    assertTrue(readBytesIfPossible(truncatedData.length, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
    assertTrue(readBytesIfPossible(truncatedData.length, outputBuffer));
    assertTrue("gzipBuffer is not stalled", gzipBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseWithPartialNextHeaderAvailable() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[1]));

    assertTrue(readBytesIfPossible(truncatedData.length, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
  }

  @Test
  public void hasPartialData() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[1]));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertTrue(gzipBuffer.hasPartialData());
  }

  @Test
  public void inflatingCompleteGzipStreamConsumesTrailer() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertTrue(!gzipBuffer.hasPartialData());
  }

  @Test
  public void getAndResetCompressedBytesConsumed() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    assertEquals(gzippedData.length, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
  }

  @Test
  public void getAndResetCompressedBytesConsumedUpdatesWithinInflateBlock() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));

    int bytesToWithhold = 1;
    assertTrue(readBytesIfPossible(truncatedData.length - bytesToWithhold, outputBuffer));
    assertEquals(
        gzippedTruncatedData.length - bytesToWithhold - GZIP_TRAILER_SIZE,
        gzipBuffer.getAndResetGzippedBytesConsumed());
    assertTrue(readBytesIfPossible(bytesToWithhold, outputBuffer));
    assertEquals(
        bytesToWithhold + GZIP_TRAILER_SIZE, gzipBuffer.getAndResetGzippedBytesConsumed());
  }

  @Test
  public void getAndResetCompressedBytesConsumedReportsHeaderFlagBytes() throws Exception {
    int bytesConsumedDelta = 0;
    gzipHeader[GZIP_HEADER_FLAG_INDEX] =
        (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }

    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gzipHeader);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT

    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());

    assertFalse(readBytesIfPossible(1, outputBuffer));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(gzipHeader.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(fExtraLen.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtra));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(fExtra.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(zeroTerminatedBytes.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(zeroTerminatedBytes.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(headerCrc16));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(headerCrc16.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(deflatedBytes.length, bytesConsumedDelta);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));
    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());

    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetGzippedBytesConsumed();
    assertEquals(gzipTrailer.length, bytesConsumedDelta);

    assertEquals(0, gzipBuffer.getAndResetGzippedBytesConsumed());
  }

  @Test
  public void wrongHeaderMagicShouldFail() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[GZIP_HEADER_MIN_SIZE]));
    try {
      gzipBuffer.inflateBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Not in GZIP format", expectedException.getMessage());
    }
  }

  @Test
  public void wrongHeaderCompressionMethodShouldFail() throws Exception {
    byte[] headerWithWrongCompressionMethod = {
      (byte) GZIP_MAGIC, (byte) (GZIP_MAGIC >> 8), 7 /* Should be 8 */, 0, 0, 0, 0, 0, 0, 0
    };
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(headerWithWrongCompressionMethod));
    try {
      gzipBuffer.inflateBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Unsupported compression method", expectedException.getMessage());
    }
  }

  @Test
  public void allHeaderFlagsWork() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] =
        (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }

    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gzipHeader);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT

    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());

    newHeader.write(headerCrc16);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(newHeader.toByteArray()));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerFTextFlagIsIgnored() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FTEXT);
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerFhcrcFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gzipHeader);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(headerCrc16));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerInvalidFhcrcFlagFails() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gzipHeader);
    headerCrc16[0] = (byte) ~headerCrc16[0];

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(headerCrc16));
    try {
      gzipBuffer.inflateBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP header", expectedException.getMessage());
    }
  }

  @Test
  public void headerFExtraFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtra));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerFExtraFlagWithZeroLenWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    byte[] fExtraLen = new byte[2];

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerFExtraFlagWithMissingExtraLenFails() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFExtraFlagWithMissingExtraBytesFails() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    int len = 5;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFNameFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FNAME);

    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerFNameFlagWithMissingBytesFail() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FNAME);
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFCommentFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FCOMMENT);
    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ;
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertTrue(readBytesIfPossible(originalData.length, outputBuffer));
    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void headerFCommentFlagWithMissingBytesFail() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FCOMMENT);

    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));
    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void requestingTooManyBytesStillReturnsEndOfBlock() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    while (gzipBuffer.inflateBytes(2 * originalData.length, outputBuffer) != 0) {}

    byte[] byteBuf = new byte[originalData.length];
    outputBuffer.readBytes(byteBuf, 0, originalData.length);
    assertTrue("inflated data does not match original", Arrays.equals(originalData, byteBuf));
  }

  @Test
  public void wrongTrailerCrcShouldFail() throws Exception {
    gzipTrailer[0] = (byte) ~gzipTrailer[0];
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  public void wrongTrailerISizeShouldFail() throws Exception {
    gzipTrailer[GZIP_TRAILER_SIZE - 1] = (byte) ~gzipTrailer[GZIP_TRAILER_SIZE - 1];
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  public void invalidDeflateBlockShouldFail() throws Exception {
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[10]));

    try {
      readBytesIfPossible(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  private byte[] getHeaderCrc16Bytes(byte[] headerBytes) {
    CRC32 crc = new CRC32();
    crc.update(headerBytes);
    byte[] headerCrc16 = {(byte) crc.getValue(), (byte) (crc.getValue() >> 8)};
    return headerCrc16;
  }

  private boolean readBytesIfPossible(int n, CompositeReadableBuffer buffer) throws Exception {
    int bytesNeeded = n;
    while (bytesNeeded > 0) {
      int bytesRead;
      if ((bytesRead = gzipBuffer.inflateBytes(bytesNeeded, buffer)) == 0) {
        return false;
      }
      bytesNeeded -= bytesRead;
    }
    return true;
  }
}
