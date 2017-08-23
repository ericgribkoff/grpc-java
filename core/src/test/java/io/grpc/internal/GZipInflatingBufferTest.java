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
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GZipInflatingBuffer}. */
@RunWith(JUnit4.class)
public class GZipInflatingBufferTest {
  private static final String UNCOMPRESSABLE_FILE = "/io/grpc/internal/uncompressable.bin";

  private static final byte[] HELLO_WORLD_GZIPPED = new byte[] {
      31, -117, 8, 0, 0, 0, 0, 0, 0, 0,  // 10 byte header
      -13, 72, -51, -55, -55, 87, 8, -49, 47, -54, 73, 1, 0, // data
      86, -79, 23, 74, 11, 0, 0, 0  // trailer
  };

  /**
   * This is the same as the above, except that the 4th header byte is 2 (FHCRC flag)
   * and the 2 bytes after the header make up the CRC.
   *
   * Constructed manually because none of the commonly used tools appear to emit header CRCs.
   */
  private static final byte[] HELLO_WORLD_GZIPPED_WITH_HEADER_CRC = new byte[] {
      31, -117, 8, 2, 0, 0, 0, 0, 0, 0,  // 10 byte header
      29, 38, // 2 byte CRC.
      -13, 72, -51, -55, -55, 87, 8, -49, 47, -54, 73, 1, 0, 86, -79, 23, 74, 11, 0, 0, 0  // data
  };

  /*(
   * This is the same as {@code HELLO_WORLD_GZIPPED} except that the 4th header byte is 4
   * (FEXTRA flag) and that the 8 bytes after the header make up the extra.
   *
   * Constructed manually because none of the commonly used tools appear to emit header CRCs.
   */
  private static final byte[] HELLO_WORLD_GZIPPED_WITH_EXTRA = new byte[] {
      31, -117, 8, 4, 0, 0, 0, 0, 0, 0,  // 10 byte header
      6, 0, 4, 2, 4, 2, 4, 2,  // 2 byte extra length + 6 byte extra.
      -13, 72, -51, -55, -55, 87, 8, -49, 47, -54, 73, 1, 0, 86, -79, 23, 74, 11, 0, 0, 0  // data
  };

  private static final byte[] TRAILER = new byte[] {
      0, 0, 0, 0, 0, 0, 0, 0
  };

  // gZipHeaderBytes: [31, -117, 8, 0, 0, 0, 0, 0, 0, 0]
  // gZipTrailerBytes: [36, 81, -101, -66, 0, 38, 0, 0]
  // gZipTrailerBytes: [-14, 56, 26, 37, 0, 4, 1, 0] - full data

  private byte[] uncompressedBytes;
  private byte[] gZipCompressedBytes;
  private byte[] gZipHeaderBytes;
  private byte[] deflatedBytes;
  private byte[] gZipTrailerBytes;

  private GZipInflatingBuffer gzipBuffer;

  private static final int GZIP_BASE_HEADER_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;

  /**
   * GZIP header magic number.
   */
  public final static int GZIP_MAGIC = 0x8b1f;

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
      byte buffer[] = new byte[512];
      int total = 0;
      int n;
      while ((n = inputStream.read(buffer)) > 0) {
        total += n;
        uncompressedOutputStream.write(buffer, 0, n);
        outputStream.write(buffer, 0, n);
        if (total > 100) {//0000) {
          break;
        }
      }
      uncompressedBytes = uncompressedOutputStream.toByteArray();
      System.out.println("total: " + total);
      outputStream.close();
      gZipCompressedBytes = byteArrayOutputStream.toByteArray();
      System.out.println("Length: " + gZipCompressedBytes.length);

      gZipHeaderBytes = Arrays.copyOf(gZipCompressedBytes, 10);
      System.out.println("gZipHeaderBytes: " + Arrays.toString(gZipHeaderBytes));
      deflatedBytes = Arrays.copyOfRange(gZipCompressedBytes, 10, gZipCompressedBytes.length - 8);
      gZipTrailerBytes = Arrays.copyOfRange(gZipCompressedBytes, gZipCompressedBytes.length - 8,
          gZipCompressedBytes.length);
      System.out.println("gZipTrailerBytes: " + Arrays.toString(gZipTrailerBytes));
    } catch (Exception e) {
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

  /*
   * File header flags.
   */
  private final static int FTEXT      = 1;    // Extra text
  private final static int FHCRC      = 2;    // Header CRC
  private final static int FEXTRA     = 4;    // Extra field
  private final static int FNAME      = 8;    // File name
  private final static int FCOMMENT   = 16;   // File comment

  private final static int HEADER_FLAG_INDEX = 3;

  // TODO - test flags, including CRC

  @Test
  public void headerFTextFlagIsIgnored() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FTEXT);
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
    }
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerFHCRCFlagWorks() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FHCRC);

    CRC32 crc = new CRC32();
    crc.update(gZipHeaderBytes);
    byte[] headerCrc16 = {(byte) crc.getValue(), (byte) (crc.getValue() >> 8)};

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(headerCrc16));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
    }
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  @Test
  public void headerInvalidFHCRCFlagFails() throws Exception {
    gZipHeaderBytes[HEADER_FLAG_INDEX] = (byte) (gZipHeaderBytes[HEADER_FLAG_INDEX] | FHCRC);

    CRC32 crc = new CRC32();
    crc.update(gZipHeaderBytes);
    byte[] headerCrc16 = {(byte) ~crc.getValue(), (byte) ~(crc.getValue() >> 8)};

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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
      if (bytesRead == 0) {
        break;
      }
      uncompressedBytesRead += bytesRead;
    }
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    try {
      while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
        bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
        if (bytesRead == 0) {
          break;
        }
        uncompressedBytesRead += bytesRead;
      }
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    try {
      while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
        bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
        if (bytesRead == 0) {
          break;
        }
        uncompressedBytesRead += bytesRead;
      }
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
      if (bytesRead == 0) {
        break;
      }
      uncompressedBytesRead += bytesRead;
    }
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    try {
      while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
        bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
        if (bytesRead == 0) {
          break;
        }
        uncompressedBytesRead += bytesRead;
      }
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

    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
      if (bytesRead == 0) {
        break;
      }
      uncompressedBytesRead += bytesRead;
    }
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    int bytesRead;
    try {
      while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
        bytesRead = gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
        if (bytesRead == 0) {
          break;
        }
        uncompressedBytesRead += bytesRead;
      }
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void gZipInflateWorks() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipCompressedBytes));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
    }
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
  }

  // TODO - concatenated streams

  @Test
  // TODO - remove need for reading 1 extra byte to trigger exception
  public void wrongTrailerCrcShouldFail() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[GZIP_TRAILER_SIZE/2]));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes, GZIP_TRAILER_SIZE/2, GZIP_TRAILER_SIZE/2));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
    }
    try {
      gzipBuffer.readUncompressedBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  // TODO - remove need for reading 1 extra byte to trigger exception
  public void wrongTrailerISizeShouldFail() throws Exception {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipHeaderBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(gZipTrailerBytes, 0, GZIP_TRAILER_SIZE/2));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(new byte[GZIP_TRAILER_SIZE/2]));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
    }
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
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    try {
      while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
        uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
      }
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue("wrong exception message", expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  // TODO:
  // HEADER with all flag combinations
  // Skippable bytes
  // Short and long Gzip block
  // Valid and invalid trailer CRC and ISIZE.

//  @Test
//  public void gzipFocusedTest() {
//    CompositeReadableBuffer buffer = new CompositeReadableBuffer();
//    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(HELLO_WORLD_GZIPPED_EXTENDED));
//    int numBytesToUncompress = 12; // TODO can increment this...
//    System.out.println("Reading " + numBytesToUncompress + ". Success? " +
//        gzipBuffer.readUncompressedBytes(numBytesToUncompress, buffer));
//    int uncompressedBytes = buffer.readableBytes();
//    System.out.println(uncompressedBytes);
//    byte[] byteBuf = new byte[uncompressedBytes];
//    buffer.readBytes(byteBuf, 0, uncompressedBytes);
//    System.out.println(bytesToHex(byteBuf));
//    assertEquals(numBytesToUncompress, uncompressedBytes);
//  }
//
//  @Test
//  public void gzipCrcTest() {
//    CompositeReadableBuffer buffer = new CompositeReadableBuffer();
//    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(HELLO_WORLD_GZIPPED_WITH_HEADER_CRC));
//    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(TRAILER));
//    gzipBuffer.readUncompressedBytes(50, buffer);
//    assertEquals(5, buffer.readableBytes());
//  }

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
