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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
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

  private static final byte[] HELLO_WORLD_GZIPPED_EXTENDED = new byte[] {
      31, -117, 8, 0, 0, 0, 0, 0, 0, 0,  // 10 byte header
      -13, 72, -51, -55, -55, 87, 8, -49, 47, -54, 73, 1, 0, // data
      64, 0, 1, 64, 63, 1, // extra data
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

  private byte[] uncompressedBytes;
  private byte[] compressedBytes;

  @Before
  public void setUp() {
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
        if (total > 10000) {
          break;
        }
        uncompressedOutputStream.write(buffer, 0, n);
        outputStream.write(buffer, 0, n);
      }
      System.out.println("total: " + total);
      outputStream.close();
      compressedBytes = byteArrayOutputStream.toByteArray();
      uncompressedBytes = uncompressedOutputStream.toByteArray();
      System.out.println("Length: " + compressedBytes.length);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void gzipInflateWorks() {
    CompositeReadableBuffer outputBuffer = new CompositeReadableBuffer();
    GZipInflatingBuffer gzipBuffer = new GZipInflatingBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(compressedBytes));
    int uncompressedBytesRead = 0;
    int bytesNeeded;
    while ((bytesNeeded = uncompressedBytes.length - uncompressedBytesRead) > 0) {
      System.out.println("needed: " + bytesNeeded);
      uncompressedBytesRead += gzipBuffer.readUncompressedBytes(bytesNeeded, outputBuffer);
    }
    assertEquals(uncompressedBytes.length, outputBuffer.readableBytes());
    byte[] byteBuf = new byte[uncompressedBytes.length];
    outputBuffer.readBytes(byteBuf, 0, uncompressedBytes.length);
    assertTrue("inflated data does not match original", Arrays.equals(uncompressedBytes, byteBuf));
//    assertEquals(uncompressedBytes, byteBuf);
  }

  // TODO:
  // HEADER with all flag combinations
  // Skippable bytes
  // Short and long Gzip block
  // Valid and invalid trailer CRC and ISIZE.

  @Test
  public void gzipFocusedTest() {
    CompositeReadableBuffer buffer = new CompositeReadableBuffer();
    GZipInflatingBuffer gzipBuffer = new GZipInflatingBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(HELLO_WORLD_GZIPPED_EXTENDED));
    int numBytesToUncompress = 12; // TODO can increment this...
    System.out.println("Reading " + numBytesToUncompress + ". Success? " +
        gzipBuffer.readUncompressedBytes(numBytesToUncompress, buffer));
    int uncompressedBytes = buffer.readableBytes();
    System.out.println(uncompressedBytes);
    byte[] byteBuf = new byte[uncompressedBytes];
    buffer.readBytes(byteBuf, 0, uncompressedBytes);
    System.out.println(bytesToHex(byteBuf));
    assertEquals(numBytesToUncompress, uncompressedBytes);
  }

  @Test
  public void gzipCrcTest() {
    CompositeReadableBuffer buffer = new CompositeReadableBuffer();
    GZipInflatingBuffer gzipBuffer = new GZipInflatingBuffer();
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(HELLO_WORLD_GZIPPED_WITH_HEADER_CRC));
    gzipBuffer.addCompressedBytes(ReadableBuffers.wrap(TRAILER));
    gzipBuffer.readUncompressedBytes(50, buffer);
    assertEquals(5, buffer.readableBytes());
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
