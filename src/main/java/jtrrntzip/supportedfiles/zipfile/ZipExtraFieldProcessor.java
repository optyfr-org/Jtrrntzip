package jtrrntzip.supportedfiles.zipfile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import jtrrntzip.ZipReturn;
import jtrrntzip.supportedfiles.UnsignedTypes;

/**
 * Parses zip extra fields for an entry.
 *
 * <p>Handles the zip64 extended information extra field (0x0001) and the
 * unicode path extra field (0x7075). Unknown extra fields are ignored.</p>
 */
final class ZipExtraFieldProcessor {

	/**
	 * Mutable holder for the fields that extra field parsing may update.
	 */
	static final class ExtraFieldState {
		boolean zip64;
		long uncompressedSize;
		long compressedSize;
		long relativeOffsetOfLocalHeader;
		String fileName;

		ExtraFieldState(boolean zip64, long uncompressedSize, long compressedSize,
				long relativeOffsetOfLocalHeader, String fileName) {
			this.zip64 = zip64;
			this.uncompressedSize = uncompressedSize;
			this.compressedSize = compressedSize;
			this.relativeOffsetOfLocalHeader = relativeOffsetOfLocalHeader;
			this.fileName = fileName;
		}
	}

	private ZipExtraFieldProcessor() {
	}

	/**
	 * Processes the extra field bytes from a central directory record.
	 *
	 * <p>Updates the provided state with zip64 sizes/offset when present and
	 * with a unicode name when the 0x7075 extra is present and valid.</p>
	 */
	static ZipReturn processCentralDirectoryExtra(byte[] extraField, int extraFieldLength,
			byte[] rawFileName, ExtraFieldState state) {
		ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
		while (extraFieldLength > bb.position()) {
			int type = UnsignedTypes.toUShort(bb.getShort());
			int blockLength = UnsignedTypes.toUShort(bb.getShort());
			int dataStart = bb.position();
			switch (type) {
				case 0x0001:
					handleZip64ExtraForCentralDir(bb, state);
					break;
				case 0x7075:
					ZipReturn r = handleUnicodeExtra(bb, blockLength, rawFileName, ZipReturn.ZIPCENTRALDIRERROR, state);
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
	 * Processes the extra field bytes from a local file header.
	 *
	 * <p>Verifies zip64 sizes against the values from the central directory
	 * and may update the unicode name. Sets zip64=true when a zip64 extra is
	 * present.</p>
	 */
	static ZipReturn processLocalFileExtra(byte[] extraField, int extraFieldLength,
			byte[] rawFileName, long expectedCompressedSize, long expectedUncompressedSize,
			ExtraFieldState state) {
		state.zip64 = false;
		ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
		while (extraFieldLength > bb.position()) {
			int type = UnsignedTypes.toUShort(bb.getShort());
			int blockLength = UnsignedTypes.toUShort(bb.getShort());
			int dataStart = bb.position();
			switch (type) {
				case 0x0001:
					ZipReturn z = handleLocalZip64Extra(bb, expectedCompressedSize, expectedUncompressedSize, state);
					if (z != ZipReturn.ZIPGOOD) {
						return z;
					}
					break;
				case 0x7075:
					ZipReturn r = handleUnicodeExtra(bb, blockLength, rawFileName, ZipReturn.ZIPLOCALFILEHEADERERROR, state);
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

	private static void handleZip64ExtraForCentralDir(ByteBuffer bb, ExtraFieldState state) {
		state.zip64 = true;
		if (state.uncompressedSize == 0xffffffffL)
			state.uncompressedSize = bb.getLong();
		if (state.compressedSize == 0xffffffffL)
			state.compressedSize = bb.getLong();
		if (state.relativeOffsetOfLocalHeader == 0xffffffffL)
			state.relativeOffsetOfLocalHeader = bb.getLong();
	}

	private static ZipReturn handleLocalZip64Extra(ByteBuffer bb, long expectedCompressedSize,
			long expectedUncompressedSize, ExtraFieldState state) {
		state.zip64 = true;
		if (expectedUncompressedSize == 0xffffffffL) {
			final long tLong = bb.getLong();
			if (tLong != state.uncompressedSize)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}
		if (expectedCompressedSize == 0xffffffffL) {
			final long tLong = bb.getLong();
			if (tLong != state.compressedSize)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}
		return ZipReturn.ZIPGOOD;
	}

	private static ZipReturn handleUnicodeExtra(ByteBuffer bb, int blockLength, byte[] rawFileName,
			ZipReturn mismatchError, ExtraFieldState state) {
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
		state.fileName = new String(dst, StandardCharsets.UTF_8);

		return ZipReturn.ZIPGOOD;
	}
}
