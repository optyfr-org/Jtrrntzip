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

/**
 * One file entry inside a zip archive: its metadata plus the streaming of
 * its data in and out of the underlying channel.
 *
 * <p>An entry owns the local file header, the data area and its copy of the
 * central directory record. The single argument constructor prepares an
 * empty entry for parsing, see {@link #centralDirectoryRead} and
 * {@link #localFileHeaderRead}; the two argument constructor prepares a new
 * entry with the torrentzip defaults for writing. The channel instance is
 * shared with the owning archive, closing a single entry also closes the
 * archive channel.</p>
 *
 * <p>Entry names follow the zip encoding rules: CP437 unless the unicode bit
 * of the entry is set, in which case UTF-8 and the unicode path extra are
 * honored, see {@link #decodeFileName} and {@link #getEncodedFileName}.</p>
 */
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

	/**
	 * Creates an empty entry bound to the channel, prepared for parsing an
	 * existing archive.
	 *
	 * @param esbc
	 *            the channel of the archive owning this entry
	 */
	public LocalFile(EnhancedSeekableByteChannel esbc) {
		this.esbc = esbc;
	}

	/**
	 * Creates a new entry for writing, initialized with the torrentzip
	 * defaults: maximum deflate compression, the matching general purpose
	 * flag and the fixed torrentzip modification timestamp.
	 *
	 * @param esbc
	 *            the channel of the archive owning this entry
	 * @param filename
	 *            the entry path inside the archive
	 */
	public LocalFile(EnhancedSeekableByteChannel esbc, String filename) {
		zip64 = false;
		this.esbc = esbc;
		generalPurposeBitFlag = 2; // Maximum Compression Deflating
		compressionMethod = 8; // Compression Method Deflate
		lastModFileTime = 48128;
		lastModFileDate = 8600;

		fileName = filename;
	}

	/**
	 * Writes the central directory record of this entry at the current
	 * position of the channel.
	 *
	 * <p>Sizes that exceed the classic 32-bit fields are moved into a zip64
	 * extra field and the classic fields store the sentinel value instead.
	 * The entry name is encoded following the zip encoding rules.</p>
	 *
	 * @param esbc
	 *            the channel of the archive being written
	 * @throws IOException
	 *             when writing the record fails
	 */
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

	/**
	 * Reads and parses the central directory record at the current position
	 * of the channel.
	 *
	 * <p>Only the stored and deflated compression methods are accepted. The
	 * extra field is scanned for the zip64 sizes and offsets and for the
	 * unicode file name extra.</p>
	 *
	 * @return {@link ZipReturn#ZIPGOOD} when the record was parsed, otherwise
	 *         the reason of the failure
	 */
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
	/**
	 * Closes the channel shared with the owning archive.
	 *
	 * <p>Since every entry of an archive shares one channel, closing a
	 * single entry also closes the whole archive.</p>
	 *
	 * @throws IOException
	 *             when closing the channel fails
	 */
	public final void close() throws IOException {
		if (this.esbc != null)
			this.esbc.close();
	}

	/**
	 * Completes a written entry that holds no data by appending the two raw
	 * deflate bytes that represent empty content.
	 *
	 * @throws IOException
	 *             when writing the bytes fails
	 */
	public final void localFileAddDirectory() throws IOException {
		esbc.put((byte) 3);
		esbc.put((byte) 0);
	}

	/**
	 * Closes the read stream of this entry.
	 *
	 * @return {@link ZipReturn#ZIPGOOD}, the stream needs no finalization
	 */
	public final ZipReturn localFileCloseReadStream() {
		return ZipReturn.ZIPGOOD;
	}

	/**
	 * Finishes the written entry: records the given CRC-32, completes empty
	 * entries with the empty deflate payload and patches the local file
	 * header with the final CRC-32 and sizes, or with the zip64 extra field
	 * for large entries.
	 *
	 * @param crc32
	 *            the CRC-32 of the written data as four little endian bytes
	 * @return {@link ZipReturn#ZIPGOOD} when the entry was finished
	 * @throws IOException
	 *             when writing the updated header fields fails
	 */
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

	/**
	 * Reads and verifies the local file header of this entry.
	 *
	 * <p>The header must agree with the central directory record on the
	 * compression method, the timestamp, the CRC-32, the sizes and the entry
	 * name, honoring the sentinel values used by zip64 entries and the zeros
	 * used by entries with a data descriptor. A differing general purpose
	 * flag only drops the torrentzip mark of the entry. On success the data
	 * position of the entry is recorded, and for entries with a data
	 * descriptor the descriptor following the data is verified too.</p>
	 *
	 * @return {@link ZipReturn#ZIPGOOD} when the header is consistent,
	 *         {@link ZipReturn#ZIPLOCALFILEHEADERERROR} otherwise
	 */
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

	/**
	 * Writes the local file header of this entry at the current position,
	 * with placeholder values for the CRC-32 and both sizes that are
	 * patched later.
	 */
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

	/**
	 * Returns a stream over the shared channel bounded to the compressed
	 * size of this entry.
	 */
	private InputStream boundedZipStream() throws IOException {
		return BoundedInputStream.builder()
				.setInputStream(esbc.getInputStream())
				.setMaxCount(compressedSize)
				.setPropagateClose(false)
				.get();
	}

	/**
	 * Opens the data stream of this entry for reading.
	 *
	 * <p>The channel is seeked to the recorded data position. Deflated
	 * entries deliver either an inflating stream or, with {@code raw} set,
	 * the stored compressed bytes; stored entries and unknown methods deliver
	 * the bounded raw data of the entry.</p>
	 *
	 * @param raw
	 *            {@code true} to return the stored compressed bytes of a
	 *            deflated entry unchanged
	 * @param inflater
	 *            the inflater shared by the archive, reset and reused
	 * @return the opened stream with its expected size and its compression
	 *         method
	 * @throws IOException
	 *             when seeking to the data position fails
	 */
	public final ICompress.OpenedReadStream localFileOpenReadStream(boolean raw, AtomicReference<Inflater> inflater) throws IOException {
		esbc.position(dataLocation);

		// compressionMethod 8 (deflate) gets an inflating stream, stored and unknown
		// methods fall back to a raw bounded stream over the stored data
		return switch (compressionMethod) {
			case 8 -> {
				if (raw)
					yield new ICompress.OpenedReadStream(ZipReturn.ZIPGOOD, boundedZipStream(), compressedSize, compressionMethod);

				if (inflater.get() == null)
					inflater.set(new Inflater(true));
				else
					inflater.get().reset();
				yield new ICompress.OpenedReadStream(ZipReturn.ZIPGOOD, new InflaterInputStream(esbc.getInputStream(), inflater.get()), getUncompressedSize(), compressionMethod);
			}
			default -> new ICompress.OpenedReadStream(ZipReturn.ZIPGOOD, boundedZipStream(), compressedSize, compressionMethod);
		};
	}

	/**
	 * Writes the local file header and opens the data stream of this entry
	 * for writing.
	 *
	 * <p>Raw entries are written unchanged and keep the passed torrentzip
	 * mark. Among the normal entries, method 8 is deflated at the maximum
	 * level into a torrentzip entry, method 0 writes the data unchanged as a
	 * plain stored entry.</p>
	 *
	 * @param raw
	 *            {@code true} to write the data unchanged as stored bytes
	 * @param tZip
	 *            {@code true} to mark a raw entry as torrentzip content
	 * @param uSize
	 *            the expected total number of bytes to be written
	 * @param cMethod
	 *            the compression method of the entry, 8 for deflate
	 * @param deflater
	 *            the deflater shared by the archive, reset and reused
	 * @return the opened write stream
	 * @throws IOException
	 *             when writing the header fails
	 */
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

	/**
	 * Reads four bytes and returns them as the little endian CRC-32 array
	 * used by the zip structures.
	 */
	private final byte[] readCRC(EnhancedSeekableByteChannel esbc) throws IOException {
		return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(esbc.getInt()).array();
	}

	/**
	 * Patches the local file header with the final CRC-32, the compressed
	 * size and the uncompressed size, writing the zip64 extra field instead
	 * when the values do not fit the classic fields.
	 */
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

	/**
	 * Stores a size for the central directory: values above the classic range
	 * are appended to the given extra field as a zip64 8-byte value and the
	 * sentinel 0xffffffff is returned for the classic field.
	 */
	private long storeSizeForCD(long value, ByteArrayOutputStream extraField) {
		if (value >= 0xffffffffL) {
			zip64 = true;
			final var buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
			extraField.write(buffer, 0, buffer.length);
			return 0xffffffffL;
		}
		return value;
	}

	/**
	 * Encodes the entry name following the zip encoding rules: CP437 when the
	 * name can be represented, UTF-8 with the unicode flag set in the
	 * general purpose bit flags otherwise.
	 */
	private byte[] getEncodedFileName() {
		if (!CP437.newEncoder().canEncode(getFileName())) {
			generalPurposeBitFlag = getGeneralPurposeBitFlag() | 1 << 11;
			return getFileName().getBytes(StandardCharsets.UTF_8);
		}
		return getFileName().getBytes(CP437);
	}

	/**
	 * Decodes an entry name: UTF-8 when the unicode flag is set in the given
	 * flags, CP437 otherwise.
	 */
	private String decodeFileName(byte[] nameBytes, int flags) {
		return (flags & (1 << 11)) == 0 ? new String(nameBytes, CP437) : new String(nameBytes, StandardCharsets.UTF_8);
	}

	/**
	 * Scans the extra field of the central directory record and applies the
	 * zip64 sizes and offsets and the unicode file name extra; unknown extra
	 * blocks are skipped.
	 */
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

	/**
	 * Applies the zip64 extra of the central directory: every classic field
	 * that holds the sentinel value is replaced by its 64-bit value from the
	 * extra data.
	 */
	private void handleZip64Extra(ByteBuffer bb) {
		zip64 = true;
		if (getUncompressedSize() == 0xffffffffL)
			uncompressedSize = bb.getLong();
		if (compressedSize == 0xffffffffL)
			compressedSize = bb.getLong();
		if (getRelativeOffsetOfLocalHeader() == 0xffffffffL)
			relativeOffsetOfLocalHeader = bb.getLong();
	}

	/**
	 * Applies the unicode path extra: the CRC-32 of the raw stored name must
	 * match the one recorded in the extra, then the entry name is replaced by
	 * the UTF-8 name the extra carries.
	 *
	 * @return the result of the check, {@link ZipReturn#ZIPGOOD} when the
	 *         extra was applied
	 */
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

	/**
	 * Verifies a size field of the local header against the central directory
	 * value, honoring the sentinel used by zip64 entries and the zeros used
	 * by entries with a data descriptor.
	 */
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

	/**
	 * Reads a short and compares it against the expected value.
	 */
	private ZipReturn readUShortAndCheck(int expected) throws IOException {
		return esbc.getUShort() == expected ? ZipReturn.ZIPGOOD : ZipReturn.ZIPLOCALFILEHEADERERROR;
	}

	/**
	 * Verifies the data descriptor following the data of this entry: the
	 * optional signature, the CRC-32 and both sizes must match the values
	 * of the central directory record.
	 */
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

	/**
	 * Scans the extra field of the local file header and applies the zip64
	 * and unicode extras. Unlike {@link #handleZip64Extra}, the zip64 sizes
	 * are verified against the central directory values instead of replacing
	 * them.
	 */
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

	/**
	 * Applies and verifies the zip64 extra of the local header: every field
	 * that holds the sentinel value is read from the extra data and compared
	 * against the central directory value.
	 */
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
	 * Returns the CRC-32 of this entry as the four little endian bytes stored
	 * in the archive.
	 *
	 * @return the CRC-32 as four little endian bytes
	 */
	public byte[] getCrc() {
		return crc;
	}

	/**
	 * Returns the decoded entry name, taken from the unicode path extra when
	 * the archive provides one.
	 *
	 * @return the entry name
	 */
	public String getFileName() {
		return fileName;
	}

	/**
	 * Returns the stored read status of this entry.
	 *
	 * <p>This implementation never updates the status, it keeps
	 * {@link ZipReturn#ZIPUNTESTED} because the read integrity is verified
	 * during the header reads.</p>
	 *
	 * @return the read status of the entry
	 */
	public ZipReturn getFileStatus() {
		return fileStatus;
	}

	/**
	 * Returns the general purpose bit flags of this entry, for example bit 3
	 * marks an entry with a data descriptor and bit 11 marks a UTF-8 encoded
	 * entry name.
	 *
	 * @return the bit flags
	 */
	public int getGeneralPurposeBitFlag() {
		return generalPurposeBitFlag;
	}

	/**
	 * Returns the file offset of the local file header of this entry.
	 *
	 * @return the offset in bytes from the start of the archive file
	 */
	public long getRelativeOffsetOfLocalHeader() {
		return relativeOffsetOfLocalHeader;
	}

	/**
	 * Returns the uncompressed size of this entry.
	 *
	 * @return the size in bytes
	 */
	public long getUncompressedSize() {
		return uncompressedSize;
	}

	/**
	 * Tells if this entry holds values that need the zip64 extensions, either
	 * because the sizes exceed the classic fields or because the archive
	 * stores them as zip64 extras.
	 *
	 * @return {@code true} when the entry uses zip64 values
	 */
	public boolean isZip64() {
		return zip64;
	}

	/**
	 * Tells if this entry carries the torrentzip mark. On the read path the
	 * mark simply survives a successful local header read with matching
	 * general purpose flags, all other header mismatches fail the read
	 * entirely. On the write path the mark is assigned by the chosen write
	 * mode. The mark alone does not prove the strict torrentzip entry layout,
	 * the full rule validation happens in
	 * {@link jtrrntzip.TorrentZipCheck}.
	 *
	 * @return {@code true} when the entry carries the torrentzip mark
	 */
	public boolean isTrrntZip() {
		return trrntZip;
	}

}
