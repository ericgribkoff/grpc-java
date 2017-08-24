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

/** Unit tests for {@link GZipInflatingBuffer}. */
@RunWith(JUnit4.class)
public class GZipInflatingBufferTest {
  private static final String UNCOMPRESSABLE_FILE = "/io/grpc/internal/uncompressable.bin";

  private byte[] uncompressedBytes;
  private byte[] gzipCompressedBytes;
  private byte[] gzipHeaderBytes;
  private byte[] deflatedBytes;
  private byte[] gzipTrailerBytes;

  private byte[] littleGZipCompressedBytes;
  private byte[] littleGZipUncompressedBytes;

  private GZipInflatingBuffer gzipBuffer;
  private CompositeReadableBuffer outputBuffer;

  private static final int GZIP_BASE_HEADER_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

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

  private static final int HEADER_FLAG_INDEX = 3;

  @Before
  public void setUp() {
    gzipBuffer = new GZipInflatingBuffer();
    outputBuffer = new CompositeReadableBuffer();
    try {
      // TODO: see if asStream works without intellij
      //      InputStream inputStream = getClass().getResourceAsStream(UNCOMPRESSABLE_FILE);
      InputStream inputStream =
          new BufferedInputStream(
              new FileInputStream(
                  "/usr/local/google/home/ericgribkoff/github/ericgribkoff/grpc-java/core/src/"
                      + "test/resources/io/grpc/internal/uncompressable.bin"));
      ByteArrayOutputStream uncompressedOutputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream smallerUncompressedOutputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      OutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream);

      ByteArrayOutputStream smallerGzippedOutputStream = new ByteArrayOutputStream();
      OutputStream smallerGzipCompressingStream = new GZIPOutputStream(smallerGzippedOutputStream);
      byte[] buffer = new byte[512];
      int total = 0;
      int n;
      while ((n = inputStream.read(buffer)) > 0) {
        total += n;
        uncompressedOutputStream.write(buffer, 0, n);
        outputStream.write(buffer, 0, n);
        if (littleGZipCompressedBytes == null) {
          smallerUncompressedOutputStream.write(buffer, 0, Math.min(n, 10));
          smallerGzipCompressingStream.write(buffer, 0, Math.min(n, 10));
          smallerGzipCompressingStream.close();
          littleGZipCompressedBytes = smallerGzippedOutputStream.toByteArray();
          littleGZipUncompressedBytes = smallerUncompressedOutputStream.toByteArray();
          System.out.println("littleGZipCompressedBytes: " + littleGZipCompressedBytes.length);
          System.out.println("littleGZipUncompressedBytes: " + littleGZipUncompressedBytes.length);

          byte[] tmp =
              Arrays.copyOfRange(
                  littleGZipCompressedBytes,
                  littleGZipCompressedBytes.length - 8,
                  littleGZipCompressedBytes.length);
          System.out.println("Correct little trailer: " + bytesToHex(tmp));
        }
        ////        if (total > 7926) {//0000) {
        //        if (total > 10000) {
        //          break;
        //        }
      }
      uncompressedBytes = uncompressedOutputStream.toByteArray();
      outputStream.close();
      gzipCompressedBytes = byteArrayOutputStream.toByteArray();

      gzipHeaderBytes = Arrays.copyOf(gzipCompressedBytes, 10);
      deflatedBytes = Arrays.copyOfRange(gzipCompressedBytes, 10, gzipCompressedBytes.length - 8);
      gzipTrailerBytes =
          Arrays.copyOfRange(
              gzipCompressedBytes, gzipCompressedBytes.length - 8, gzipCompressedBytes.length);
    } catch (Exception e) {
      e.printStackTrace(System.out);
      fail("Failed to set up compressed data");
    }
  }

  @After
  public void tearDown() {
    gzipBuffer.close();
    outputBuffer.close();
  }

  @Test
  public void gzipInflateWorks() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  // TODO - one byte at a time test

  @Test
  public void concatenatedStreamsWorks() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertTrue(readBytesIfPossible(littleGZipUncompressedBytes.length, outputBuffer));
    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertTrue(readBytesIfPossible(littleGZipUncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));

    byteBuf = new byte[littleGZipUncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, littleGZipUncompressedBytes.length);
    assertTrue(
        "inflated data does not match original",
        Arrays.equals(littleGZipUncompressedBytes, byteBuf));

    byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));

    byteBuf = new byte[littleGZipUncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, littleGZipUncompressedBytes.length);
    assertTrue(
        "inflated data does not match original",
        Arrays.equals(littleGZipUncompressedBytes, byteBuf));
  }

  @Test
  public void readUncompressedBytesWithLargerRequest_doesNotOverflow() throws Exception {
    gzipBuffer.readUncompressedBytes(1, outputBuffer);
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
  }

  @Test
  public void closeStopsDecompression() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    assertTrue(readBytesIfPossible(1, outputBuffer));
    gzipBuffer.close();
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expectedException) {
      assertEquals("GZipInflatingBuffer is closed", expectedException.getMessage());
    }
  }

  @Test
  public void isStalledReturnsTrueAtEndOfStream() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    int bytesToWithhold = 10;
    assertTrue(readBytesIfPossible(uncompressedBytes.length - bytesToWithhold, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
    assertTrue(readBytesIfPossible(bytesToWithhold, outputBuffer));
    assertTrue("gzipBuffer is not stalled", gzipBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseBetweenStreams() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertTrue("gzipBuffer is not stalled", gzipBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseBetweenSmallStreams() throws Exception {
    // Use small streams to make sure that they all fit in the inflater buffer
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));

    assertTrue(readBytesIfPossible(littleGZipUncompressedBytes.length, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
    assertTrue(readBytesIfPossible(littleGZipUncompressedBytes.length, outputBuffer));
    assertTrue("gzipBuffer is not stalled", gzipBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseWithPartialNextHeaderAvailable() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[1]));

    assertTrue(readBytesIfPossible(littleGZipUncompressedBytes.length, outputBuffer));
    assertFalse("gzipBuffer is stalled", gzipBuffer.isStalled());
  }

  @Test
  public void hasPartialData() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[1]));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertTrue(gzipBuffer.hasPartialData());
  }

  @Test
  public void inflatingCompleteGzipStreamConsumesTrailer() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertTrue(!gzipBuffer.hasPartialData());
  }

  @Test
  public void getAndResetCompressedBytesConsumed() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    assertEquals(gzipCompressedBytes.length, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
  }

  @Test
  public void getAndResetCompressedBytesConsumedUpdatesWithinInflateBlock() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));

    int bytesToWithhold = 1;
    assertTrue(
        readBytesIfPossible(littleGZipUncompressedBytes.length - bytesToWithhold, outputBuffer));
    assertEquals(
        littleGZipCompressedBytes.length - bytesToWithhold - GZIP_TRAILER_SIZE,
        gzipBuffer.getAndResetCompressedBytesConsumed());
    assertTrue(readBytesIfPossible(bytesToWithhold, outputBuffer));
    assertEquals(
        bytesToWithhold + GZIP_TRAILER_SIZE, gzipBuffer.getAndResetCompressedBytesConsumed());
  }

  @Test
  public void getAndResetCompressedBytesConsumedReportsHeaderFlagBytes() throws Exception {
    int bytesConsumedDelta = 0;
    gzipHeaderBytes[HEADER_FLAG_INDEX] =
        (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ;

    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gzipHeaderBytes);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT

    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());

    assertFalse(readBytesIfPossible(1, outputBuffer));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(gzipHeaderBytes.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(fExtraLen.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtra));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(fExtra.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(zeroTerminatedBytes.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(zeroTerminatedBytes.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerCrc16));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(headerCrc16.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(deflatedBytes.length, bytesConsumedDelta);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));
    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());

    assertFalse(readBytesIfPossible(1, outputBuffer));
    bytesConsumedDelta = gzipBuffer.getAndResetCompressedBytesConsumed();
    assertEquals(gzipTrailerBytes.length, bytesConsumedDelta);

    assertEquals(0, gzipBuffer.getAndResetCompressedBytesConsumed());
  }

  @Test
  public void wrongHeaderMagicShouldFail() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[GZIP_BASE_HEADER_SIZE]));
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
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
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerWithWrongCompressionMethod));
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Unsupported compression method", expectedException.getMessage());
    }
  }

  @Test
  public void allHeaderFlagsWork() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] =
        (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ;

    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gzipHeaderBytes);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT

    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());
    System.out.println("headerCrc16 bytes" + bytesToHex(headerCrc16));

    newHeader.write(headerCrc16);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(newHeader.toByteArray()));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFTextFlagIsIgnored() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FTEXT);
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFhcrcFlagWorks() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gzipHeaderBytes);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerCrc16));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerInvalidFhcrcFlagFails() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gzipHeaderBytes);
    headerCrc16[0] = (byte) ~headerCrc16[0];

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerCrc16));
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP header", expectedException.getMessage());
    }
  }

  @Test
  public void headerFExtraFlagWorks() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtra));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFExtraFlagWithZeroLenWorks() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    byte[] fExtraLen = new byte[2];

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFExtraFlagWithMissingExtraLenFails() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFExtraFlagWithMissingExtraBytesFails() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    int len = 5;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFNameFlagWorks() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FNAME);

    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ;

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFNameFlagWithMissingBytesFail() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FNAME);
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFCommentFlagWorks() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FCOMMENT);
    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ;
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFCommentFlagWithMissingBytesFail() throws Exception {
    gzipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gzipHeaderBytes[HEADER_FLAG_INDEX] | FCOMMENT);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));
    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  // TODO this is ugly to have to test. Should we change the API to return whatever it has
  // (up to the requested amount) each time? Is this hard to do?
  public void requestingTooManyBytesStillReturnsEndOfBlock() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipCompressedBytes));

    while (gzipBuffer.readUncompressedBytes(2 * uncompressedBytes.length, outputBuffer) != 0) {}

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  // TODO - remove need for reading 1 extra byte to trigger exception
  public void wrongTrailerCrcShouldFail() throws Exception {
    gzipTrailerBytes[0] = (byte) ~gzipTrailerBytes[0];
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    //    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      //      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  // TODO - test for requesting too much data with some in the return buffer, should
  // still get all data

  @Test
  // TODO - remove need for reading 1 extra byte to trigger exception
  public void wrongTrailerISizeShouldFail() throws Exception {
    gzipTrailerBytes[GZIP_TRAILER_SIZE - 1] = (byte) ~gzipTrailerBytes[GZIP_TRAILER_SIZE - 1];
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipTrailerBytes));

    //    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      //      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  public void invalidDeflateBlockShouldFail() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gzipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[10]));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
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
    System.out.println("ReadBytesIfPossible called with n=" + n);
    int bytesNeeded = n;
    while (bytesNeeded > 0) {
      int bytesRead;
      System.out.println("Requesting " + bytesNeeded + " bytes");
      if ((bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, buffer)) == 0) {
        System.out.println(
            "Giving up readBytesIfPossible with bytesNeeded="
                + bytesNeeded
                + " and readableBytes in buffer="
                + buffer.readableBytes());
        return false;
      }
      System.out.println("received " + bytesRead + " bytesRead");
      bytesNeeded -= bytesRead;
    }
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
