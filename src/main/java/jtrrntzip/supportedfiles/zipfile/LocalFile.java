package jtrrntzip.supportedfiles.zipfile;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.apache.commons.io.input.BoundedInputStream;

import jtrrntzip.ZipReturn;
import jtrrntzip.supportedfiles.EnhancedSeekableByteChannel;
import jtrrntzip.supportedfiles.ICompress;
import jtrrntzip.supportedfiles.UnsignedTypes;

public final class LocalFile implements Closeable {
	private long compressedSize;

	private int compressionMethod;
	private long crc32Location;
	private long dataLocation;
	private long extraLocation;
	private int generalPurposeBitFlag;
	private int lastModFileDate;
	private int lastModFileTime;

	private boolean trrntZip;

	private long uncompressedSize;
	private OutputStream writeStream;
	private byte[] crc;

	private final EnhancedSeekableByteChannel esbc;

	private String fileName;

	private ZipReturn fileStatus = ZipReturn.ZIPUNTESTED;

	private long relativeOffsetOfLocalHeader; // only in central directory

	private boolean zip64;

	private static final Charset CP437 = Charset.forName("Cp437"); //$NON-NLS-1$

	private static final Logger LOGGER = Logger.getLogger(LocalFile.class.getName());

	public LocalFile(EnhancedSeekableByteChannel esbc) {
		this.esbc = esbc;
	}

	public LocalFile(EnhancedSeekableByteChannel esbc, String filename) {
		zip64 = false;
		this.esbc = esbc;
		generalPurposeBitFlag = 2; // Maximum Compression Deflating
		compressionMethod = 8; // Compression Method Deflate
		lastModFileTime = 48128;
		lastModFileDate = 8600;

		fileName = filename;
	}

	public final void centralDirectoryWrite(EnhancedSeekableByteChannel esbc) throws IOException {

		final var header = 0x02014B50;

		final var extraField = new ByteArrayOutputStream();
		final long cdUncompressedSize = storeSizeForCD(getUncompressedSize(), extraField);
		final long cdCompressedSize = storeSizeForCD(compressedSize, extraField);
		final long cdRelativeOffsetOfLocalHeader = storeSizeForCD(getRelativeOffsetOfLocalHeader(), extraField);

		var bExtraField = extraField.toByteArray();
		if (bExtraField.length > 0) {
			final var full = new byte[bExtraField.length + 4];
			ByteBuffer.wrap(full, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
					.putShort(UnsignedTypes.fromUShort(0x0001))
					.putShort(UnsignedTypes.fromUShort(bExtraField.length));
			System.arraycopy(bExtraField, 0, full, 4, bExtraField.length);
			bExtraField = full;
		}

		final byte[] bFileName = getEncodedFileName();

		final int versionNeededToExtract = (isZip64() ? 45 : 20);

		esbc.putInt(header); // 4
		esbc.putUShort(0); // 6
		esbc.putUShort(versionNeededToExtract); // 8
		esbc.putUShort(getGeneralPurposeBitFlag()); // 10
		esbc.putUShort(compressionMethod); // 12
		esbc.putUShort(lastModFileTime); // 14
		esbc.putUShort(lastModFileDate); // 16
		esbc.put(getCrc()); // 20
		esbc.putUInt(cdCompressedSize); // 24
		esbc.putUInt(cdUncompressedSize); // 28
		esbc.putUShort(bFileName.length); // 30
		esbc.putUShort(bExtraField.length); // 32
		esbc.putUShort(0); // 34 // file comment length
		esbc.putUShort(0); // 36 // disk number start
		esbc.putUShort(0); // 38 // internal file attributes
		esbc.putUInt(0); // 42 // external file attributes
		esbc.putUInt(cdRelativeOffsetOfLocalHeader); // 46
		esbc.put(bFileName);
		esbc.put(bExtraField);
		// No File Comment
	}

	public final ZipReturn centralDirectoryRead() {
		try {

			final var thisSignature = esbc.getInt();
			if (thisSignature != ZipFile.CENTRALDIRECTORYHEADERSIGNATURE)
				return ZipReturn.ZIPCENTRALDIRERROR;

			esbc.getUShort(); // Version Made By

			esbc.getUShort(); // Version Needed To Extract

			generalPurposeBitFlag = esbc.getUShort();
			compressionMethod = esbc.getUShort();
			if (compressionMethod != 8 && compressionMethod != 0)
				return ZipReturn.ZIPUNSUPPORTEDCOMPRESSION;

			lastModFileTime = esbc.getUShort();
			lastModFileDate = esbc.getUShort();
			crc = readCRC(esbc);

			compressedSize = esbc.getUInt();
			uncompressedSize = esbc.getUInt();

			int fileNameLength = esbc.getUShort();
			int extraFieldLength = esbc.getUShort();
			int fileCommentLength = esbc.getUShort();

			esbc.getUShort(); // diskNumberStart
			esbc.getUShort(); // internalFileAttributes
			esbc.getUInt(); // externalFileAttributes

			relativeOffsetOfLocalHeader = esbc.getUInt();

			final var bFileName = new byte[fileNameLength];
			esbc.get(bFileName);
			fileName = decodeFileName(bFileName, getGeneralPurposeBitFlag());

			final var extraField = new byte[extraFieldLength];
			esbc.get(extraField);

			esbc.position(esbc.position() + fileCommentLength); // File Comments

			ZipReturn extraRet = processExtraField(extraField, extraFieldLength, bFileName);
			if (extraRet != ZipReturn.ZIPGOOD)
				return extraRet;

			return ZipReturn.ZIPGOOD;
		} catch (Exception e) {
			LOGGER.log(Level.FINE, "error reading central directory entry", e);
			return ZipReturn.ZIPCENTRALDIRERROR;
		}

	}

	@Override
	public final void close() throws IOException {
		if (this.esbc != null)
			this.esbc.close();
	}

	public final void localFileAddDirectory() throws IOException {
		esbc.put((byte) 3);
		esbc.put((byte) 0);
	}

	public final ZipReturn localFileCloseReadStream() {
		return ZipReturn.ZIPGOOD;
	}

	public final ZipReturn localFileCloseWriteStream(byte[] crc32) throws IOException {
		OutputStream dfStream = writeStream;
		if (dfStream != null) {
			dfStream.flush();
		}

		compressedSize = esbc.position() - dataLocation;

		if (compressedSize == 0x0L && getUncompressedSize() == 0x0L) {
			localFileAddDirectory();
			compressedSize = esbc.position() - dataLocation;
		}

		crc = crc32;
		writeCompressedSize();
		return ZipReturn.ZIPGOOD;
	}

	public final ZipReturn localFileHeaderRead() {
		try {
			trrntZip = true;

			esbc.position(getRelativeOffsetOfLocalHeader());
			final var thisSignature = esbc.getInt();
			if (thisSignature != ZipFile.LOCALFILEHEADERSIGNATURE)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			esbc.getUShort(); // version needed to extract
			final int generalPurposeBitFlagLocal = esbc.getUShort();
			if (generalPurposeBitFlagLocal != getGeneralPurposeBitFlag())
				trrntZip = false;

			if (readUShortAndCheck(compressionMethod) != ZipReturn.ZIPGOOD)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
			if (readUShortAndCheck(lastModFileTime) != ZipReturn.ZIPGOOD)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
			if (readUShortAndCheck(lastModFileDate) != ZipReturn.ZIPGOOD)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			byte[] tCRC = readCRC(esbc);
			if (((getGeneralPurposeBitFlag() & 8) == 0) && !Arrays.equals(tCRC, getCrc()))
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			final long tCompressedSize = esbc.getUInt();
			if (checkLocalSize(tCompressedSize, compressedSize, isZip64(), getGeneralPurposeBitFlag()) != ZipReturn.ZIPGOOD)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			final long tUnCompressedSize = esbc.getUInt();
			if (checkLocalSize(tUnCompressedSize, getUncompressedSize(), isZip64(), getGeneralPurposeBitFlag()) != ZipReturn.ZIPGOOD)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			final int fileNameLength = esbc.getUShort();
			final int extraFieldLength = esbc.getUShort();

			final var bFileName = new byte[fileNameLength];
			esbc.get(bFileName);
			String tFileName = decodeFileName(bFileName, generalPurposeBitFlagLocal);

			final var extraField = new byte[extraFieldLength];
			esbc.get(extraField);

			ZipReturn extraRet = processLocalExtraField(extraField, extraFieldLength, tCompressedSize, tUnCompressedSize, bFileName);
			if (extraRet != ZipReturn.ZIPGOOD)
				return extraRet;

			if (!getFileName().equals(tFileName))
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			dataLocation = esbc.position();

			if ((getGeneralPurposeBitFlag() & 8) != 0) {
				ZipReturn dd = verifyDataDescriptor();
				if (dd != ZipReturn.ZIPGOOD)
					return dd;
			}

			return ZipReturn.ZIPGOOD;
		} catch (Exception e) {
			LOGGER.log(Level.FINE, "error reading local file header", e);
			return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}

	}

	private final void localFileHeaderWrite() throws IOException {
		zip64 = getUncompressedSize() >= 0xffffffffL;

		final byte[] bFileName = getEncodedFileName();

		final int versionNeededToExtract = (isZip64() ? 45 : 20);

		relativeOffsetOfLocalHeader = esbc.position();
		final var header = 0x4034B50;

		esbc.putUInt(header); // 4
		esbc.putUShort(versionNeededToExtract); // 8
		esbc.putUShort(getGeneralPurposeBitFlag()); // 10
		esbc.putUShort(compressionMethod); // 12
		esbc.putUShort(lastModFileTime); // 14
		esbc.putUShort(lastModFileDate); // 16

		crc32Location = esbc.position();

		// these 3 values will be set correctly after the file data has been
		// written
		esbc.putUInt(0xFFFFFFFFL);
		esbc.putUInt(0xFFFFFFFFL);
		esbc.putUInt(0xFFFFFFFFL);

		final byte[] extraField = isZip64() ? new byte[20] : new byte[0];

		esbc.putUShort(bFileName.length);
		esbc.putUShort(extraField.length);

		esbc.put(bFileName);

		extraLocation = esbc.position();
		esbc.put(extraField);
	}

	private InputStream boundedZipStream() throws IOException {
		return BoundedInputStream.builder()
				.setInputStream(esbc.getInputStream())
				.setMaxCount(compressedSize)
				.setPropagateClose(false)
				.get();
	}

	public final ICompress.OpenedReadStream localFileOpenReadStream(boolean raw, AtomicReference<Inflater> inflater) throws IOException {
		esbc.position(dataLocation);

		switch (compressionMethod) {
			case 8:
			{
				if (raw)
					return new ICompress.OpenedReadStream(ZipReturn.ZIPGOOD, boundedZipStream(), compressedSize, compressionMethod);

				if (inflater.get() == null)
					inflater.set(new Inflater(true));
				else
					inflater.get().reset();
				return new ICompress.OpenedReadStream(ZipReturn.ZIPGOOD, new InflaterInputStream(esbc.getInputStream(), inflater.get()), getUncompressedSize(), compressionMethod);
			}
			default:
			case 0:
			{
				return new ICompress.OpenedReadStream(ZipReturn.ZIPGOOD, boundedZipStream(), compressedSize, compressionMethod);
			}
		}
	}

	public final ICompress.OpenedWriteStream localFileOpenWriteStream(boolean raw, boolean tZip, long uSize, int cMethod, AtomicReference<Deflater> deflater) throws IOException {
		uncompressedSize = uSize;
		compressionMethod = cMethod;

		localFileHeaderWrite();
		dataLocation = esbc.position();

		if (raw) {
			writeStream = esbc.getOutputStream();
			trrntZip = tZip;
		} else {
			if (cMethod == 0) {
				writeStream = esbc.getOutputStream();
				trrntZip = false;
			} else {
				if (deflater.get() == null)
					deflater.set(new Deflater(9, true));
				else
					deflater.get().reset();
				writeStream = new DeflaterOutputStream(esbc.getOutputStream(), deflater.get(), false);
				trrntZip = true;
			}
		}

		return new ICompress.OpenedWriteStream(ZipReturn.ZIPGOOD, writeStream);
	}

	private final byte[] readCRC(EnhancedSeekableByteChannel esbc) throws IOException {
		return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(esbc.getInt()).array();
	}

	private final void writeCompressedSize() throws IOException {
		final long posNow = esbc.position();

		esbc.position(crc32Location);

		final long tCompressedSize;
		final long tUncompressedSize;
		if (isZip64()) {
			tCompressedSize = 0xffffffffL;
			tUncompressedSize = 0xffffffffL;
		} else {
			tCompressedSize = compressedSize;
			tUncompressedSize = getUncompressedSize();
		}

		esbc.put(getCrc());
		esbc.putUInt(tCompressedSize);
		esbc.putUInt(tUncompressedSize);

		// also need to write extradata
		if (isZip64()) {
			esbc.position(extraLocation);
			esbc.putUShort(0x0001); // id
			esbc.putUShort(16); // data length
			esbc.putULong(getUncompressedSize());
			esbc.putULong(compressedSize);
		}

		esbc.position(posNow);

	}

	private long storeSizeForCD(long value, ByteArrayOutputStream extraField) {
		if (value >= 0xffffffffL) {
			zip64 = true;
			final var buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
			extraField.write(buffer, 0, buffer.length);
			return 0xffffffffL;
		}
		return value;
	}

	private byte[] getEncodedFileName() {
		if (!CP437.newEncoder().canEncode(getFileName())) {
			generalPurposeBitFlag = getGeneralPurposeBitFlag() | 1 << 11;
			return getFileName().getBytes(StandardCharsets.UTF_8);
		}
		return getFileName().getBytes(CP437);
	}

	private String decodeFileName(byte[] nameBytes, int flags) {
		return (flags & (1 << 11)) == 0 ? new String(nameBytes, CP437) : new String(nameBytes, StandardCharsets.UTF_8);
	}

	private ZipReturn processExtraField(byte[] extraField, int extraFieldLength, byte[] rawFileName) {
		ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
		while (extraFieldLength > bb.position()) {
			int type = UnsignedTypes.toUShort(bb.getShort());
			int blockLength = UnsignedTypes.toUShort(bb.getShort());
			int dataStart = bb.position();
			switch (type) {
				case 0x0001:
					handleZip64Extra(bb);
					break;
				case 0x7075:
					ZipReturn r = handleUnicodeExtra(bb, blockLength, rawFileName, ZipReturn.ZIPCENTRALDIRERROR);
					if (r != ZipReturn.ZIPGOOD) {
						return r;
					}
					break;
				default:
					break;
			}
			bb.position(dataStart + blockLength);
		}
		return ZipReturn.ZIPGOOD;
	}

	private void handleZip64Extra(ByteBuffer bb) {
		zip64 = true;
		if (getUncompressedSize() == 0xffffffffL)
			uncompressedSize = bb.getLong();
		if (compressedSize == 0xffffffffL)
			compressedSize = bb.getLong();
		if (getRelativeOffsetOfLocalHeader() == 0xffffffffL)
			relativeOffsetOfLocalHeader = bb.getLong();
	}

	private ZipReturn handleUnicodeExtra(ByteBuffer bb, int blockLength, byte[] rawFileName, ZipReturn mismatchError) {
		@SuppressWarnings("unused")
		final byte version = bb.get();
		final long nameCRC32 = UnsignedTypes.toUInt(bb.getInt());

		final var crcTest = new java.util.zip.CRC32();
		crcTest.update(rawFileName);
		final long fCRC = crcTest.getValue();

		if (nameCRC32 != fCRC)
			return mismatchError;

		if (blockLength < 5)
			return mismatchError;

		final int charLen = blockLength - 5;

		final var dst = new byte[charLen];
		bb.get(dst);
		fileName = new String(dst, StandardCharsets.UTF_8);

		return ZipReturn.ZIPGOOD;
	}

	private ZipReturn checkLocalSize(long readValue, long centralValue, boolean isZip64, int generalPurposeBitFlag) {
		boolean dataDescriptor = (generalPurposeBitFlag & 8) == 8;
		if (isZip64 && readValue != 0xffffffffL && readValue != centralValue)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
		if (dataDescriptor && readValue != 0)
			return ZipReturn.ZIPLOCALFILEHEADERERROR;
		if (!isZip64 && !dataDescriptor && readValue != centralValue)
			return ZipReturn.ZIPLOCALFILEHEADERERROR;
		return ZipReturn.ZIPGOOD;
	}

	private ZipReturn readUShortAndCheck(int expected) throws IOException {
		return esbc.getUShort() == expected ? ZipReturn.ZIPGOOD : ZipReturn.ZIPLOCALFILEHEADERERROR;
	}

	private ZipReturn verifyDataDescriptor() throws IOException {
		esbc.position(esbc.position() + compressedSize);

		byte[] tCRC = readCRC(esbc);
		if (Arrays.equals(tCRC, new byte[] { 0x50, 0x4b, 0x07, 0x08 }))
			tCRC = readCRC(esbc);

		if (!Arrays.equals(tCRC, getCrc()))
			return ZipReturn.ZIPLOCALFILEHEADERERROR;

		long tint = esbc.getUInt();
		if (tint != compressedSize)
			return ZipReturn.ZIPLOCALFILEHEADERERROR;

		tint = esbc.getUInt();
		if (tint != getUncompressedSize())
			return ZipReturn.ZIPLOCALFILEHEADERERROR;

		return ZipReturn.ZIPGOOD;
	}

	private ZipReturn processLocalExtraField(byte[] extraField, int extraFieldLength, long tCompressedSize, long tUnCompressedSize, byte[] rawFileName) {
		zip64 = false;
		ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
		while (extraFieldLength > bb.position()) {
			int type = UnsignedTypes.toUShort(bb.getShort());
			int blockLength = UnsignedTypes.toUShort(bb.getShort());
			int dataStart = bb.position();
			switch (type) {
				case 0x0001:
					ZipReturn z = handleLocalZip64Extra(bb, tCompressedSize, tUnCompressedSize);
					if (z != ZipReturn.ZIPGOOD) {
						return z;
					}
					break;
				case 0x7075:
					ZipReturn r = handleUnicodeExtra(bb, blockLength, rawFileName, ZipReturn.ZIPLOCALFILEHEADERERROR);
					if (r != ZipReturn.ZIPGOOD) {
						return r;
					}
					break;
				default:
					break;
			}
			bb.position(dataStart + blockLength);
		}
		return ZipReturn.ZIPGOOD;
	}

	private ZipReturn handleLocalZip64Extra(ByteBuffer bb, long tCompressedSize, long tUnCompressedSize) {
		zip64 = true;
		if (tUnCompressedSize == 0xffffffffL) {
			final long tLong = bb.getLong();
			if (tLong != getUncompressedSize())
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}
		if (tCompressedSize == 0xffffffffL) {
			final long tLong = bb.getLong();
			if (tLong != compressedSize)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}
		return ZipReturn.ZIPGOOD;
	}

	/**
	 * @return the crc
	 */
	public byte[] getCrc() {
		return crc;
	}

	/**
	 * @return the fileName
	 */
	public String getFileName() {
		return fileName;
	}

	/**
	 * @return the fileStatus
	 */
	public ZipReturn getFileStatus() {
		return fileStatus;
	}

	/**
	 * @return the generalPurposeBitFlag
	 */
	public int getGeneralPurposeBitFlag() {
		return generalPurposeBitFlag;
	}

	/**
	 * @return the relativeOffsetOfLocalHeader
	 */
	public long getRelativeOffsetOfLocalHeader() {
		return relativeOffsetOfLocalHeader;
	}

	/**
	 * @return the uncompressedSize
	 */
	public long getUncompressedSize() {
		return uncompressedSize;
	}

	/**
	 * @return the zip64
	 */
	public boolean isZip64() {
		return zip64;
	}

	/**
	 * @return the trrntZip
	 */
	public boolean isTrrntZip() {
		return trrntZip;
	}

}
