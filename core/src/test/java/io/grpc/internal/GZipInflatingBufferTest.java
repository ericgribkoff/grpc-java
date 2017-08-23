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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.primitives.Ints;

import java.io.*;
import java.util.Arrays;
import java.util.zip.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GZipInflatingBuffer}. */
@RunWith(JUnit4.class)
public class GZipInflatingBufferTest {
  private static final String UNCOMPRESSABLE_FILE = "/io/grpc/internal/uncompressable.bin";

  private byte[] uncompressedBytes;
  private byte[] gZipCompressedBytes;
  private byte[] gZipHeaderBytes;
  private byte[] deflatedBytes;
  private byte[] gZipTrailerBytes;

  private byte[] littleGZipCompressedBytes;
  private byte[] littleGZipUncompressedBytes;

  private GZipInflatingBuffer gzipBuffer;

  private static final int GZIP_BASE_HEADER_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  /**
   * GZIP header magic number.
   */
  public final static int GZIP_MAGIC = 0x8b1f;

  /*
   * File header flags.
   */
  private final static int FTEXT      = 1;    // Extra text
  private final static int FHCRC      = 2;    // Header CRC
  private final static int FEXTRA     = 4;    // Extra field
  private final static int FNAME      = 8;    // File name
  private final static int FCOMMENT   = 16;   // File comment

  private final static int HEADER_FLAG_INDEX = 3;

  @Before
  public void setUp() {
    gzipBuffer = new GZipInflatingBuffer();
    try {
      // TODO: see if asStream works without intellij
//      InputStream inputStream = getClass().getResourceAsStream(UNCOMPRESSABLE_FILE);
      InputStream inputStream =
          new BufferedInputStream(
              new FileInputStream(
                  "/usr/local/google/home/ericgribkoff/github/ericgribkoff/grpc-java/core/src/test/resources/io/grpc/internal/uncompressable.bin"));
      ByteArrayOutputStream uncompressedOutputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      OutputStream outputStream = new GZIPOutputStream(byteArrayOutputStream);

      ByteArrayOutputStream smallerGzippedOutputStream = new ByteArrayOutputStream();
      OutputStream smallerGzipCompressingStream = new GZIPOutputStream(smallerGzippedOutputStream);
      byte buffer[] = new byte[512];
      int total = 0;
      int n;
      while ((n = inputStream.read(buffer)) > 0) {
        total += n;
        uncompressedOutputStream.write(buffer, 0, n);
        outputStream.write(buffer, 0, n);
        if (littleGZipCompressedBytes == null) {
          smallerGzipCompressingStream.write(buffer, 0, n);
          smallerGzipCompressingStream.close();
          littleGZipCompressedBytes = smallerGzippedOutputStream.toByteArray();
          littleGZipUncompressedBytes = uncompressedOutputStream.toByteArray();
        }
//        if (total > 7926) {//0000) {
        if (total > 10000) {
          break;
        }
      }
      uncompressedBytes = uncompressedOutputStream.toByteArray();
      outputStream.close();
      gZipCompressedBytes = byteArrayOutputStream.toByteArray();

      gZipHeaderBytes = Arrays.copyOf(gZipCompressedBytes, 10);
      deflatedBytes = Arrays.copyOfRange(gZipCompressedBytes, 10, gZipCompressedBytes.length - 8);
      gZipTrailerBytes = Arrays.copyOfRange(gZipCompressedBytes, gZipCompressedBytes.length - 8,
          gZipCompressedBytes.length);
    } catch (Exception e) {
      e.printStackTrace(System.out);
      fail("Failed to set up compressed data");
    }
  }

  @Test
  public void wrongHeaderMagicShouldFail() throws Exception {
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[GZIP_BASE_HEADER_SIZE]));
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
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
        (byte) GZIP_MAGIC,
        (byte) (GZIP_MAGIC >> 8),
        7 /* Should be 8 */, 0, 0, 0, 0, 0, 0, 0
    };
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerWithWrongCompressionMethod));
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Unsupported compression method", expectedException.getMessage());
    }
  }

  @Test
  public void allHeaderFlagsWork() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] =
        (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FTEXT | FHCRC| FEXTRA | FNAME | FCOMMENT);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    };

    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gZipHeaderBytes);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT

    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());
    System.out.println("headerCrc16 bytes" + bytesToHex(headerCrc16));

    newHeader.write(headerCrc16);

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(newHeader.toByteArray()));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFTextFlagIsIgnored() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FTEXT);
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFHCRCFlagWorks() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gZipHeaderBytes);

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerCrc16));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerInvalidFHCRCFlagFails() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gZipHeaderBytes);
    headerCrc16[0] = (byte) ~headerCrc16[0];

    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerCrc16));
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP header", expectedException.getMessage());
    }
  }

  @Test
  public void headerFExtraFlagWorks() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtra));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFExtraFlagWithZeroLenWorks() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    byte[] fExtraLen = new byte[2];

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFExtraFlagWithMissingExtraLenFails() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFExtraFlagWithMissingExtraBytesFails() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FEXTRA);

    int len = 5;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFNameFlagWorks() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FNAME);

    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    };

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFNameFlagWithMissingBytesFail() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FNAME);
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFCommentFlagWorks() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FCOMMENT);
    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    };
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFCommentFlagWithMissingBytesFail() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FCOMMENT);

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));
    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void gZipInflateWorks() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipCompressedBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void concatenatedStreamsWorks() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(littleGZipCompressedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipCompressedBytes));
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
    assertTrue("inflated data does not match original", Arrays.equals(littleGZipUncompressedBytes, byteBuf));

    byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));

    byteBuf = new byte[littleGZipUncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, littleGZipUncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(littleGZipUncompressedBytes, byteBuf));
  }

  @Test
  // TODO this is ugly to have to test. Should we change the API to return whatever it has
  // (up to the requested amount) each time? Is this hard to do?
  public void requestingTooManyBytesStillReturnsEndOfBlock() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipCompressedBytes));

    while (gzipBuffer.readUncompressedBytes(2*uncompressedBytes.length, outputBuffer) != 0) {
    }

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void reassembledGZipInflateWorks() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  // TODO - remove need for reading 1 extra byte to trigger exception
  public void wrongTrailerCrcShouldFail() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gZipTrailerBytes[0] = (byte) ~gZipTrailerBytes[0];
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));

    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
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
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gZipTrailerBytes[GZIP_TRAILER_SIZE-1] = (byte) ~gZipTrailerBytes[GZIP_TRAILER_SIZE-1];
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));

    assertTrue(readBytesIfPossible(uncompressedBytes.length, outputBuffer));
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  public void invalidDeflateBlockShouldFail() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[10]));

    try {
      readBytesIfPossible(uncompressedBytes.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
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
        System.out.println("Giving up readBytesIfPossible with bytesNeeded=" + bytesNeeded + " and readableBytes in buffer=" +
        buffer.readableBytes());
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
