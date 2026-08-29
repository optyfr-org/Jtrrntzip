package jtrrntzip.supportedfiles;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public final class EnhancedSeekableByteChannel implements SeekableByteChannel {
    private final SeekableByteChannel sbc;
    private ByteOrder bo;
    private Checksum checksum = null;

    private final ByteBuffer lbb = ByteBuffer.allocate(8);
    private final ByteBuffer ibb = ByteBuffer.allocate(4);
    private final ByteBuffer sbb = ByteBuffer.allocate(2);
    private final ByteBuffer bbb = ByteBuffer.allocate(1);

    public EnhancedSeekableByteChannel(final SeekableByteChannel sbc, final ByteOrder bo) {
        this.sbc = sbc;
        order(bo);
    }

    public EnhancedSeekableByteChannel order(final ByteOrder bo) {
        this.bo = bo;
        lbb.order(this.bo);
        ibb.order(this.bo);
        sbb.order(this.bo);
        return this;
    }

    public final ByteOrder order() {
        return bo;
    }

    public final EnhancedSeekableByteChannel put(final byte b) throws IOException {
        bbb.clear();
        bbb.put(b);
        bbb.rewind();
        writeFully(bbb);
        if (checksum != null)
            checksum.update(b);
        return this;
    }

    public final EnhancedSeekableByteChannel put(final byte[] b) throws IOException {
        writeFully(ByteBuffer.wrap(b));
        if (checksum != null)
            checksum.update(b, 0, b.length);
        return this;
    }

    public final EnhancedSeekableByteChannel put(final byte[] b, final int offset, final int len) throws IOException {
        writeFully(ByteBuffer.wrap(b, offset, len));
        if (checksum != null)
            checksum.update(b, offset, len);
        return this;
    }

    public final EnhancedSeekableByteChannel putLong(final long l) throws IOException {
        lbb.clear();
        lbb.putLong(l);
        lbb.rewind();
        writeFully(lbb);
        if (checksum != null)
            checksum.update(lbb.array(), 0, 8);
        return this;
    }

    public final EnhancedSeekableByteChannel putULong(final BigInteger l) throws IOException {
        return putLong(UnsignedTypes.fromULong(l));
    }

    public final EnhancedSeekableByteChannel putInt(final int i) throws IOException {
        ibb.clear();
        ibb.putInt(i);
        ibb.rewind();
        writeFully(ibb);
        if (checksum != null)
            checksum.update(ibb.array(), 0, 4);
        return this;
    }

    public final EnhancedSeekableByteChannel putUInt(final long i) throws IOException {
        return putInt(UnsignedTypes.fromUInt(i));
    }

    public final EnhancedSeekableByteChannel putShort(final short s) throws IOException {
        sbb.clear();
        sbb.putShort(s);
        sbb.rewind();
        writeFully(sbb);
        if (checksum != null)
            checksum.update(sbb.array(), 0, 2);
        return this;
    }

    public final EnhancedSeekableByteChannel putUShort(final int s) throws IOException {
        return putShort(UnsignedTypes.fromUShort(s));
    }

    public final byte get() throws IOException {
        bbb.clear();
        readFully(bbb);
        bbb.flip();
        final byte b = bbb.get();
        if (checksum != null)
            checksum.update(b);
        return b;
    }

    public final EnhancedSeekableByteChannel get(final byte[] dst) throws IOException {
        readFully(ByteBuffer.wrap(dst));
        if (checksum != null)
            checksum.update(dst, 0, dst.length);
        return this;
    }

    public final EnhancedSeekableByteChannel get(final byte[] dst, final int offset, final int len) throws IOException {
        readFully(ByteBuffer.wrap(dst, offset, len));
        if (checksum != null)
            checksum.update(dst, offset, len);
        return this;
    }

    public final long getLong() throws IOException {
        lbb.clear();
        readFully(lbb);
        if (checksum != null)
            checksum.update(lbb.array(), 0, 8);
        lbb.rewind();
        return lbb.getLong();
    }

    public final BigInteger getULong() throws IOException {
        return UnsignedTypes.toULong(getLong());
    }

    public final int getInt() throws IOException {
        ibb.clear();
        readFully(ibb);
        if (checksum != null)
            checksum.update(ibb.array(), 0, 4);
        ibb.rewind();
        return ibb.getInt();
    }

    public final long getUInt() throws IOException {
        return UnsignedTypes.toUInt(getInt());
    }

    public final short getShort() throws IOException {
        sbb.clear();
        readFully(sbb);
        if (checksum != null)
            checksum.update(sbb.array(), 0, 2);
        sbb.rewind();
        return sbb.getShort();
    }

    public final int getUShort() throws IOException {
        return UnsignedTypes.toUShort(getShort());
    }

    public final InputStream getInputStream() {
        return Channels.newInputStream(this);
    }

    public final OutputStream getOutputStream() {
        return Channels.newOutputStream(this);
    }

    @Override
    public final boolean isOpen() {
        return sbc.isOpen();
    }

    @Override
    public final void close() throws IOException {
        sbc.close();
    }

    @Override
    public final int read(final ByteBuffer dst) throws IOException {
        return sbc.read(dst);
    }

    @Override
    public final int write(final ByteBuffer src) throws IOException {
        return sbc.write(src);
    }

    @Override
    public final long position() throws IOException {
        return sbc.position();
    }

    @Override
    public final SeekableByteChannel position(final long newPosition) throws IOException {
        sbc.position(newPosition);
        return this;
    }

    @Override
    public final long size() throws IOException {
        return sbc.size();
    }

    @Override
    public final SeekableByteChannel truncate(final long size) throws IOException {
        sbc.truncate(size);
        return this;
    }

    public final void startChecksum() {
        checksum = new CRC32();
    }

    public final long endChecksum() {
        if (checksum == null)
            throw new IllegalStateException("checksum was not started"); //$NON-NLS-1$
        final long value = checksum.getValue();
        checksum = null;
        return value;
    }

    private void readFully(final ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            final int n = sbc.read(dst);
            if (n < 0)
                throw new EOFException();
            if (n == 0)
                throw new IOException("zero-length read"); //$NON-NLS-1$
        }
    }

    private void writeFully(final ByteBuffer src) throws IOException {
        while (src.hasRemaining()) {
            final int n = sbc.write(src);
            if (n == 0)
                throw new IOException("zero-length write"); //$NON-NLS-1$
        }
    }

}
