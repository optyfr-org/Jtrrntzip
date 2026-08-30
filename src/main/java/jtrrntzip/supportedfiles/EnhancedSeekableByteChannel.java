package jtrrntzip.supportedfiles;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * A {@link SeekableByteChannel} wrapper adding the primitives needed to read
 * and write binary archive structures.
 *
 * <p>All multi byte operations honor the byte order configured with
 * {@link #order(ByteOrder)}, which makes the little endian layout of the zip
 * format directly readable and writable with
 * {@link #getInt()}/{@link #putInt(int)} style helpers. Reads always fill the
 * requested amount of bytes: a channel that ends early causes an
 * {@link EOFException} instead of silently returning short data. An optional
 * CRC-32 checksum, started with {@link #startChecksum()} and read with
 * {@link #endChecksum()}, covers every byte transferred afterwards through
 * the primitive get and put helpers of this class; the torrentzip file
 * comment verification relies on it.</p>
 *
 * <p>The raw {@link #read(ByteBuffer)} and {@link #write(ByteBuffer)} methods
 * and the {@link #getInputStream()}/{@link #getOutputStream()} stream views
 * do not feed the checksum, only the typed helper operations do.</p>
 *
 * <p>The {@link #getInputStream()} and {@link #getOutputStream()} views share
 * the position of this channel, reading and writing advance it like any other
 * operation of this class.</p>
 */
public final class EnhancedSeekableByteChannel implements SeekableByteChannel {
    private final SeekableByteChannel sbc;
    private ByteOrder bo;
    private Checksum checksum = null;

    private final ByteBuffer lbb = ByteBuffer.allocate(8);
    private final ByteBuffer ibb = ByteBuffer.allocate(4);
    private final ByteBuffer sbb = ByteBuffer.allocate(2);
    private final ByteBuffer bbb = ByteBuffer.allocate(1);

    /**
     * Creates a channel wrapper with the given byte order.
     *
     * @param sbc
     *            the underlying channel receiving all transferred bytes
     * @param bo
     *            the byte order used by all multi byte operations
     */
    public EnhancedSeekableByteChannel(final SeekableByteChannel sbc, final ByteOrder bo) {
        this.sbc = sbc;
        order(bo);
    }

    /**
     * Sets the byte order used by all multi byte operations.
     *
     * @param bo
     *            the new byte order
     * @return this channel
     */
    public EnhancedSeekableByteChannel order(final ByteOrder bo) {
        this.bo = bo;
        lbb.order(this.bo);
        ibb.order(this.bo);
        sbb.order(this.bo);
        return this;
    }

    /**
     * Returns the byte order currently used by all multi byte operations.
     *
     * @return the current byte order
     */
    public final ByteOrder order() {
        return bo;
    }

    /**
     * Writes a byte at the current position and advances it.
     *
     * @param b
     *            the byte to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel put(final byte b) throws IOException {
        bbb.clear();
        bbb.put(b);
        bbb.rewind();
        writeFully(bbb);
        if (checksum != null)
            checksum.update(b);
        return this;
    }

    /**
     * Writes all bytes of the array at the current position and advances the
     * position by the array length.
     *
     * @param b
     *            the bytes to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel put(final byte[] b) throws IOException {
        writeFully(ByteBuffer.wrap(b));
        if (checksum != null)
            checksum.update(b, 0, b.length);
        return this;
    }

    /**
     * Writes {@code len} bytes of the array, starting at {@code offset}, at
     * the current position and advances the position accordingly.
     *
     * @param b
     *            the array holding the bytes to write
     * @param offset
     *            the start offset of the written data inside the array
     * @param len
     *            the number of bytes to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel put(final byte[] b, final int offset, final int len) throws IOException {
        writeFully(ByteBuffer.wrap(b, offset, len));
        if (checksum != null)
            checksum.update(b, offset, len);
        return this;
    }

    /**
     * Writes a long at the current position and advances it by eight.
     *
     * @param l
     *            the value to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel putLong(final long l) throws IOException {
        lbb.clear();
        lbb.putLong(l);
        lbb.rewind();
        writeFully(lbb);
        if (checksum != null)
            checksum.update(lbb.array(), 0, 8);
        return this;
    }

    /**
     * Writes the unsigned variant of the long at the current position and
     * advances it by eight, see {@link #putLong(long)}.
     *
     * @param l
     *            the value to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel putULong(final long l) throws IOException {
        return putLong(l);
    }

    /**
     * Writes an int at the current position and advances it by four.
     *
     * @param i
     *            the value to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel putInt(final int i) throws IOException {
        ibb.clear();
        ibb.putInt(i);
        ibb.rewind();
        writeFully(ibb);
        if (checksum != null)
            checksum.update(ibb.array(), 0, 4);
        return this;
    }

    /**
     * Writes the unsigned variant of the int at the current position and
     * advances it by four, see {@link #putInt(int)}.
     *
     * @param i
     *            the unsigned 32-bit value to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel putUInt(final long i) throws IOException {
        return putInt(UnsignedTypes.fromUInt(i));
    }

    /**
     * Writes a short at the current position and advances it by two.
     *
     * @param s
     *            the value to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel putShort(final short s) throws IOException {
        sbb.clear();
        sbb.putShort(s);
        sbb.rewind();
        writeFully(sbb);
        if (checksum != null)
            checksum.update(sbb.array(), 0, 2);
        return this;
    }

    /**
     * Writes the unsigned variant of the short at the current position and
     * advances it by two, see {@link #putShort(short)}.
     *
     * @param s
     *            the unsigned 16-bit value to write
     * @return this channel
     * @throws IOException
     *             when writing fails
     */
    public final EnhancedSeekableByteChannel putUShort(final int s) throws IOException {
        return putShort(UnsignedTypes.fromUShort(s));
    }

    /**
     * Reads a byte and advances the position by one.
     *
     * @return the byte read
     * @throws IOException
     *             when reading fails or the channel ends before one byte is
     *             available
     */
    public final byte get() throws IOException {
        bbb.clear();
        readFully(bbb);
        bbb.flip();
        final byte b = bbb.get();
        if (checksum != null)
            checksum.update(b);
        return b;
    }

    /**
     * Fills the array with bytes read from the channel and advances the
     * position accordingly.
     *
     * @param dst
     *            the array receiving the bytes
     * @return this channel
     * @throws IOException
     *             when reading fails or the channel ends before the array is
     *             full
     */
    public final EnhancedSeekableByteChannel get(final byte[] dst) throws IOException {
        readFully(ByteBuffer.wrap(dst));
        if (checksum != null)
            checksum.update(dst, 0, dst.length);
        return this;
    }

    /**
     * Reads {@code len} bytes into the array, starting at {@code offset}, and
     * advances the position accordingly.
     *
     * @param dst
     *            the array receiving the bytes
     * @param offset
     *            the start offset of the received data inside the array
     * @param len
     *            the number of bytes to read
     * @return this channel
     * @throws IOException
     *             when reading fails or the channel ends before {@code len}
     *             bytes were read
     */
    public final EnhancedSeekableByteChannel get(final byte[] dst, final int offset, final int len) throws IOException {
        readFully(ByteBuffer.wrap(dst, offset, len));
        if (checksum != null)
            checksum.update(dst, offset, len);
        return this;
    }

    /**
     * Reads a long and advances the position by eight.
     *
     * @return the value read in the configured byte order
     * @throws IOException
     *             when reading fails or the channel ends before eight bytes
     *             are available
     */
    public final long getLong() throws IOException {
        lbb.clear();
        readFully(lbb);
        if (checksum != null)
            checksum.update(lbb.array(), 0, 8);
        lbb.rewind();
        return lbb.getLong();
    }

    /**
     * Reads an unsigned 64-bit value and advances the position by eight, see
     * {@link #getLong()}.
     *
     * @return the value read
     * @throws IOException
     *             when reading fails or the channel ends early
     */
    public final long getULong() throws IOException {
        return getLong();
    }

    /**
     * Reads an int and advances the position by four.
     *
     * @return the value read in the configured byte order
     * @throws IOException
     *             when reading fails or the channel ends before four bytes
     *             are available
     */
    public final int getInt() throws IOException {
        ibb.clear();
        readFully(ibb);
        if (checksum != null)
            checksum.update(ibb.array(), 0, 4);
        ibb.rewind();
        return ibb.getInt();
    }

    /**
     * Reads an unsigned 32-bit value and advances the position by four, see
     * {@link #getInt()}.
     *
     * @return the unsigned value in the range 0 to 2^32-1
     * @throws IOException
     *             when reading fails or the channel ends before four bytes
     *             are available
     */
    public final long getUInt() throws IOException {
        return UnsignedTypes.toUInt(getInt());
    }

    /**
     * Reads a short and advances the position by two.
     *
     * @return the value read in the configured byte order
     * @throws IOException
     *             when reading fails or the channel ends before two bytes are
     *             available
     */
    public final short getShort() throws IOException {
        sbb.clear();
        readFully(sbb);
        if (checksum != null)
            checksum.update(sbb.array(), 0, 2);
        sbb.rewind();
        return sbb.getShort();
    }

    /**
     * Reads an unsigned 16-bit value and advances the position by two, see
     * {@link #getShort()}.
     *
     * @return the unsigned value in the range 0 to 65535
     * @throws IOException
     *             when reading fails or the channel ends before two bytes are
     *             available
     */
    public final int getUShort() throws IOException {
        return UnsignedTypes.toUShort(getShort());
    }

    /**
     * Returns an input stream view sharing the position of this channel;
     * reading from the stream advances the channel.
     *
     * @return the stream view
     */
    public final InputStream getInputStream() {
        return Channels.newInputStream(this);
    }

    /**
     * Returns an output stream view sharing the position of this channel;
     * writing to the stream advances the channel.
     *
     * @return the stream view
     */
    public final OutputStream getOutputStream() {
        return Channels.newOutputStream(this);
    }

    /**
     * Tells if the underlying channel is still open.
     *
     * @return {@code true} when the channel is open
     */
    @Override
    public final boolean isOpen() {
        return sbc.isOpen();
    }

    /**
     * Closes the underlying channel.
     *
     * @throws IOException
     *             when closing fails
     */
    @Override
    public final void close() throws IOException {
        sbc.close();
    }

    /**
     * Reads from the underlying channel into the buffer, see
     * {@link SeekableByteChannel#read(ByteBuffer)}.
     *
     * @param dst
     *            the buffer receiving the bytes
     * @return the number of bytes read, possibly 0, or -1 at the end
     * @throws IOException
     *             when reading fails
     */
    @Override
    public final int read(final ByteBuffer dst) throws IOException {
        return sbc.read(dst);
    }

    /**
     * Writes from the buffer to the underlying channel, see
     * {@link SeekableByteChannel#write(ByteBuffer)}.
     *
     * @param src
     *            the buffer holding the bytes
     * @return the number of bytes written, possibly 0
     * @throws IOException
     *             when writing fails
     */
    @Override
    public final int write(final ByteBuffer src) throws IOException {
        return sbc.write(src);
    }

    /**
     * Returns the position of the underlying channel.
     *
     * @return the current position
     * @throws IOException
     *             when querying the position fails
     */
    @Override
    public final long position() throws IOException {
        return sbc.position();
    }

    /**
     * Sets the position of the underlying channel.
     *
     * @param newPosition
     *            the position to seek to
     * @return this channel
     * @throws IOException
     *             when seeking fails
     */
    @Override
    public final SeekableByteChannel position(final long newPosition) throws IOException {
        sbc.position(newPosition);
        return this;
    }

    /**
     * Returns the size of the underlying channel.
     *
     * @return the current size in bytes
     * @throws IOException
     *             when querying the size fails
     */
    @Override
    public final long size() throws IOException {
        return sbc.size();
    }

    /**
     * Truncates the underlying channel to the given size.
     *
     * @param size
     *            the new size in bytes
     * @return this channel
     * @throws IOException
     *             when truncating fails
     */
    @Override
    public final SeekableByteChannel truncate(final long size) throws IOException {
        sbc.truncate(size);
        return this;
    }

    /**
     * Starts a CRC-32 checksum over every byte that is transferred afterwards
     * through the primitive get and put helpers of this class. Bytes moved
     * with the raw read and write methods or through the stream views do not
     * feed the checksum. A previously started checksum is dropped without
     * being read.
     */
    public final void startChecksum() {
        checksum = new CRC32();
    }

    /**
     * Returns the checksum started with {@link #startChecksum()} and stops
     * the checksumming.
     *
     * @return the CRC-32 of all bytes transferred since the checksum started
     * @throws IllegalStateException
     *             when no checksum was started
     */
    public final long endChecksum() {
        if (checksum == null)
            throw new IllegalStateException("checksum was not started"); //$NON-NLS-1$
        final long value = checksum.getValue();
        checksum = null;
        return value;
    }

    /**
     * Fills the buffer completely from the underlying channel and fails with
     * an {@link EOFException} when the channel ends early.
     *
     * @param dst
     *            the buffer to fill
     * @throws IOException
     *             when reading fails or a zero length read occurs
     */
    private void readFully(final ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            final int n = sbc.read(dst);
            if (n < 0)
                throw new EOFException();
            if (n == 0)
                throw new IOException("zero-length read"); //$NON-NLS-1$
        }
    }

    /**
     * Drains the buffer completely into the underlying channel.
     *
     * @param src
     *            the buffer to write
     * @throws IOException
     *             when writing fails or a zero length write occurs
     */
    private void writeFully(final ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            final int n = sbc.write(src);
            if (n == 0)
                throw new IOException("zero-length write"); //$NON-NLS-1$
        }
    }

}
