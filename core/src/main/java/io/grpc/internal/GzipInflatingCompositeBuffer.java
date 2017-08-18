package io.grpc.internal;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * Created by ericgribkoff on 8/17/17.
 */
public class GzipInflatingCompositeBuffer implements CompositeBuffer {
  private final CompositeReadableBuffer compressedData = new CompositeReadableBuffer();
  private final CompositeReadableBuffer uncompressedData = new CompositeReadableBuffer();

  private final static int INFLATE_BUFFER_SIZE = 512;
  private byte[] buf = new byte[INFLATE_BUFFER_SIZE];

//  private final static int MAX_GZIP_HEADER_SIZE = 18;
//  private byte[] headerBuf = new byte[MAX_GZIP_HEADER_SIZE];
//  private int headerBufIndex = 0;

  private CompositeReadableBuffer nextBlock; // Either gzip header, deflated block, or gzip trailer

  @Override
  public void addBuffer(ReadableBuffer buffer) {
    compressedData.addBuffer(buffer);
  }

  @Override
  public int readableBytes() {
    int uncompressedBytes = uncompressedData.readableBytes();
    if (uncompressedBytes > 0) {
      return uncompressedBytes;
    }
    return inflate();
  }

  @Override
  public CompositeReadableBuffer readBytes(int length) {
    return uncompressedData.readBytes(length);
  }

  @Override
  public void close() {
    compressedData.close();
  }

  private final static int BASE_GZIP_HEADER_SIZE = 10;

  private Inflater inflater = new Inflater(true);
  private State state = State.HEADER;

  private int requiredLength = BASE_GZIP_HEADER_SIZE;

  private enum State {
    HEADER, HEADER_EXTRA, BODY, TRAILER
  }

  private int inflate() {
    // TODO pending deliveries? should be replaced with buffer fill size, double-check
    while (readRequiredBytes()) {
      switch (state) {
        case HEADER:
          processHeader();
          break;
//          if (readHeader()) {
//            state = State.BODY;
//          }
//          byte[] buf = new byte[HEADER_LENGTH];
//          nextFrame.readBytes(buf, 0, HEADER_LENGTH);
//          System.out.println("HEADER: " + bytesToHex(buf));
//          try {
//            readHeader(new ByteArrayInputStream(buf));
//          } catch (IOException e) {
//            System.out.println("failed to readHeader");
//            e.printStackTrace(System.out);
//          }
//          nextFrame.close();
//          state = State.BODY;
//          requiredLength = BYTES_TO_READ;
//          break;
        case BODY:
          // Pass the body bytes to the inflater
          processBody();
          break;
        case TRAILER:
          // TODO something
          throw new AssertionError("Reached TRAILER");
        default:
          throw new AssertionError("Invalid state: " + state);
      }
    }
    return 0;
  }

  /**
   * Attempts to read the required bytes into nextFrame.
   *
   * @return {@code true} if all of the required bytes have been read.
   */
  private boolean readRequiredBytes() {
    int totalBytesRead = 0;
    try {
      if (nextBlock == null) {
        nextBlock = new CompositeReadableBuffer();
      }

      // Read until the buffer contains all the required bytes.
      int missingBytes;
      while ((missingBytes = requiredLength - nextBlock.readableBytes()) > 0) {
        if (compressedData.readableBytes() == 0) {
          // No more data is available.
          return false;
        }
        int toRead = Math.min(missingBytes, compressedData.readableBytes());
        totalBytesRead += toRead;
        nextBlock.addBuffer(compressedData.readBytes(toRead));
      }
      return true;
    } finally {
      if (totalBytesRead > 0) {
        // TODO get a listener
        listener.bytesRead(totalBytesRead);
      }
    }
  }

  private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
  public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for ( int j = 0; j < bytes.length; j++ ) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

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

  /**
   * CRC-32 for uncompressed data.
   */
  protected CRC32 crc = new CRC32();

  private void processHeader() {
    crc.reset();

    // TODO - handle CRC

    // Check header magic
    if (readUShort() != GZIP_MAGIC) {
      System.out.println("Not in GZIP Format");
      throw new RuntimeException();
      //throw new ZipException("Not in GZIP format");
    }

    // Check compression method
    if (nextBlock.readUnsignedByte() != 8) {
      System.out.println("Unsupported compression method");
      throw new RuntimeException();
      //throw new ZipException("Unsupported compression method");
    }
    // Read flags
    int flg = nextBlock.readUnsignedByte();
    // Skip MTIME, XFL, and OS fields
    nextBlock.readBytes(6); // TODO crc
    int n = 2 + 2 + 6;

    // TODO handle optional additional bytes here
    state = State.BODY;
  }


  private void processBody() {
    //ReadableBuffers.openStream(nextFrame, true);
    byte[] inputBuf = new byte[BYTES_TO_READ];
    nextFrame.readBytes(inputBuf, 0, BYTES_TO_READ);
    System.out.println("Raw byte read: " + bytesToHex(inputBuf));
    if (inflater.needsInput()) {
      System.out.println("Input needed");
      inflater.setInput(inputBuf);
    } else {
      System.out.println("No input needed");
    }
    byte[] decompressedBuf = new byte[100];
    try {
      if (inflater.finished()) {
        System.out.println("Finished!");
        return;
      }
      int n = inflater.inflate(decompressedBuf, 0, 100);
      while (n > 0) {
        System.out.println("INFLATED: (" + n + ") " + bytesToHex(decompressedBuf));
        if (n > 0) {
          System.out.println("Inflated " + n + " bytes");
          deframer.deframe(ReadableBuffers.wrap(decompressedBuf, 0, n));
        }
        n = inflater.inflate(decompressedBuf, 0, 100);
      }
    } catch (DataFormatException e) {
      System.out.println("DataFormatException");
      e.printStackTrace(System.out);
    }
  }

  /*
 * Reads unsigned short in Intel byte order.
 */
  private int readUShort() {
    int b = nextBlock.readUnsignedByte();
    return (nextBlock.readUnsignedByte() << 8) | b;
  }

  /*
 * Reads GZIP member header and returns the total byte number
 * of this member header.
 */
  private boolean readHeader() throws IOException {
    crc.reset();

    // Check header magic
    if (readUShort(in) != GZIP_MAGIC) {
      throw new ZipException("Not in GZIP format");
    }
    // Check compression method
    if (readUByte(in) != 8) {
      throw new ZipException("Unsupported compression method");
    }
    // Read flags
    int flg = readUByte(in);
    // Skip MTIME, XFL, and OS fields
    skipBytes(in, 6);
    int n = 2 + 2 + 6;
    // Skip optional extra field
    if ((flg & FEXTRA) == FEXTRA) {
      int m = readUShort(in);
      skipBytes(in, m);
      n += m + 2;
    }
    // Skip optional file name
    if ((flg & FNAME) == FNAME) {
      do {
        n++;
      } while (readUByte(in) != 0);
    }
    // Skip optional file comment
    if ((flg & FCOMMENT) == FCOMMENT) {
      do {
        n++;
      } while (readUByte(in) != 0);
    }
    // Check optional header CRC
    if ((flg & FHCRC) == FHCRC) {
      int v = (int)crc.getValue() & 0xffff;
      if (readUShort(in) != v) {
        throw new ZipException("Corrupt GZIP header");
      }
      n += 2;
    }
    crc.reset();
    return n;
  }

//  /*
//   * Reads GZIP member trailer and returns true if the eos
//   * reached, false if there are more (concatenated gzip
//   * data set)
//   */
//  private boolean readTrailer() throws IOException {
//    InputStream in = this.in;
//    int n = inf.getRemaining();
//    if (n > 0) {
//      in = new SequenceInputStream(
//              new ByteArrayInputStream(buf, len - n, n),
//              new FilterInputStream(in) {
//                public void close() throws IOException {}
//              });
//    }
//    // Uses left-to-right evaluation order
//    if ((readUInt(in) != crc.getValue()) ||
//            // rfc1952; ISIZE is the input size modulo 2^32
//            (readUInt(in) != (inf.getBytesWritten() & 0xffffffffL)))
//      throw new ZipException("Corrupt GZIP trailer");
//
//    // If there are more bytes available in "in" or
//    // the leftover in the "inf" is > 26 bytes:
//    // this.trailer(8) + next.header.min(10) + next.trailer(8)
//    // try concatenated case
//    if (this.in.available() > 0 || n > 26) {
//      int m = 8;                  // this.trailer
//      try {
//        m += readHeader(in);    // next.header
//      } catch (IOException ze) {
//        return true;  // ignore any malformed, do nothing
//      }
//      inf.reset();
//      if (n > m)
//        inf.setInput(buf, len - n + m, n - m);
//      return false;
//    }
//    return true;
//  }

  /*
 * Reads unsigned integer in Intel byte order.
 */
  private long readUInt(InputStream in) throws IOException {
    long s = readUShort(in);
    return ((long)readUShort(in) << 16) | s;
  }

  /*
   * Reads unsigned short in Intel byte order.
   */
  private int readUShort(InputStream in) throws IOException {
    int b = readUByte(in);
    return (readUByte(in) << 8) | b;
  }

  /*
   * Reads unsigned byte.
   */
  private int readUByte(InputStream in) throws IOException {
    int b = in.read();
    if (b == -1) {
      throw new EOFException();
    }
    if (b < -1 || b > 255) {
      // Report on this.in, not argument in; see read{Header, Trailer}.
      throw new IOException(this.getClass().getName()
          + ".read() returned value out of range -1..255: " + b);
    }
    return b;
  }

  private byte[] tmpbuf = new byte[128];

  /*
   * Skips bytes of input data blocking until all bytes are skipped.
   * Does not assume that the input stream is capable of seeking.
   */
  private void skipBytes(InputStream in, int n) throws IOException {
    while (n > 0) {
      int len = in.read(tmpbuf, 0, n < tmpbuf.length ? n : tmpbuf.length);
      if (len == -1) {
        throw new EOFException();
      }
      n -= len;
    }
  }

}
