package jtrrntzip.supportedfiles.zipfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import jtrrntzip.ZipReturn;
import jtrrntzip.ZipStatus;
import jtrrntzip.supportedfiles.EnhancedSeekableByteChannel;

/**
 * Handles the End of Central Directory structures for a zip archive.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Locate the classic EOCD by scanning backwards.</li>
 *   <li>Read and write the classic EOCD record.</li>
 *   <li>Read and write the zip64 EOCD locator and zip64 EOCD record when
 *       required.</li>
 *   <li>Detect when zip64 structures must be written based on sizes and counts.</li>
 * </ul>
 * </p>
 *
 * <p>This class owns the directory-level size/offset/count/comment/zip64 state
 * that was previously scattered across ZipFile.</p>
 */
final class EndOfCentralDirectory {

	private static final int ENDOFCENTRALDIRSIGNATURE = 0x06054b50;
	private static final int ZIP64ENDOFCENTRALDIRECTORYLOCATOR = 0x07064b50;
	private static final int ZIP64ENDOFCENTRALDIRSIGNATURE = 0x06064b50;

	/** Size of the central directory in bytes. */
	long centerDirSize;
	/** Offset of the start of the central directory. */
	long centerDirStart;
	/** Offset of the zip64 EOCD record (when used). */
	long endOfCenterDir64;

	/** Number of entries according to the EOCD record(s). */
	long localFilesCount;
	/** The raw file comment bytes from the EOCD. */
	byte[] fileComment;

	/** True when zip64 EOCD structures are (or must be) used. */
	boolean zip64;

	/** Set by read() when bytes remain after the EOCD record. */
	boolean extraDataPresent;

	EndOfCentralDirectory() {
	}

	/**
	 * Searches backwards for the classic EOCD signature.
	 */
	ZipReturn findSignature(EnhancedSeekableByteChannel esbc) throws IOException {
		long fileSize = esbc.size();

		var maxBackSearch = 0xffffL;

		if (esbc.size() < maxBackSearch)
			maxBackSearch = fileSize;

		final var buffSize = 0x400;

		final var buffer = new byte[buffSize + 4];

		long backPosition = 4;
		while (backPosition < maxBackSearch) {
			backPosition += buffSize;
			if (backPosition > maxBackSearch)
				backPosition = maxBackSearch;

			long readSize = backPosition > (buffSize + 4) ? (buffSize + 4) : backPosition;

			esbc.position(fileSize - backPosition);

			esbc.get(buffer, 0, (int) readSize);

			for (int i = (int) readSize - 4; i >= 0; i--) {
				if ((buffer[i] != 0x50) || (buffer[i + 1] != 0x4b) || (buffer[i + 2] != 0x05) || (buffer[i + 3] != 0x06))
					continue;

				esbc.position((fileSize - backPosition) + i);
				return ZipReturn.ZIPGOOD;
			}
		}
		return ZipReturn.ZIPCENTRALDIRERROR;
	}

	/**
	 * Reads the classic EOCD at the current position.
	 */
	ZipReturn read(EnhancedSeekableByteChannel esbc) throws IOException {
		final long thisSignature = esbc.getInt();
		if (thisSignature != ENDOFCENTRALDIRSIGNATURE)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		int tushort = esbc.getUShort(); // NumberOfThisDisk
		if (tushort != 0)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		tushort = esbc.getUShort(); // NumberOfThisDiskCenterDir
		if (tushort != 0)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		localFilesCount = esbc.getUShort(); // TotalNumberOfEnteriesDisk

		tushort = esbc.getUShort(); // TotalNumber of entries in the central directory
		if (tushort != localFilesCount)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		centerDirSize = esbc.getUInt(); // SizeOfCenteralDir
		centerDirStart = esbc.getUInt(); // Offset

		int zipFileCommentLength = esbc.getUShort();

		fileComment = new byte[zipFileCommentLength];
		esbc.get(fileComment);

		extraDataPresent = esbc.position() != esbc.size();

		return ZipReturn.ZIPGOOD;
	}

	/**
	 * Writes the classic EOCD record at the current position.
	 *
	 * @param entryCount
	 *            the number of entries to record (may use 0xffff sentinel)
	 */
	void write(EnhancedSeekableByteChannel esbc, int entryCount) throws IOException {
		esbc.putUInt(ENDOFCENTRALDIRSIGNATURE);
		esbc.putUShort(0); // NumberOfThisDisk
		esbc.putUShort(0); // NumberOfThisDiskCenterDir
		esbc.putUShort((entryCount > 0xffff ? 0xffff : entryCount)); // TotalNumberOfEnteriesDisk
		esbc.putUShort((entryCount > 0xffff ? 0xffff : entryCount)); // TotalNumber of entries in the central directory
		esbc.putUInt((centerDirSize >= 0xffffffffL ? 0xffffffffL : centerDirSize)); // SizeOfCenteralDir
		esbc.putUInt((centerDirStart >= 0xffffffffL ? 0xffffffffL : centerDirStart)); // Offset
		esbc.putUShort(fileComment.length);
		esbc.put(fileComment, 0, fileComment.length);
	}

	/**
	 * Reads the zip64 EOCD locator at the current position.
	 */
	ZipReturn readZip64Locator(EnhancedSeekableByteChannel esbc) throws IOException {
		zip64 = true;

		final long thisSignature = esbc.getUInt();
		if (thisSignature != ZIP64ENDOFCENTRALDIRECTORYLOCATOR)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		long tuint = esbc.getUInt(); // number of the disk with the start of the zip64 end of centeral directory
		if (tuint != 0)
			return ZipReturn.ZIP64ENDOFCENTRALDIRECTORYLOCATORERROR;

		endOfCenterDir64 = esbc.getULong(); // relative offset of the zip64 end of central directroy record

		tuint = esbc.getUInt(); // total number of disks
		if (tuint != 1)
			return ZipReturn.ZIP64ENDOFCENTRALDIRECTORYLOCATORERROR;

		return ZipReturn.ZIPGOOD;
	}

	/**
	 * Writes the zip64 EOCD locator.
	 */
	void writeZip64Locator(EnhancedSeekableByteChannel esbc) throws IOException {
		esbc.putInt(ZIP64ENDOFCENTRALDIRECTORYLOCATOR);
		esbc.putUInt(0); // number of the disk with the start of the zip64 end of centeral directory
		esbc.putULong(endOfCenterDir64); // relative offset of the zip64 end of central directroy record
		esbc.putUInt(1); // total number of disks
	}

	/**
	 * Reads the zip64 EOCD record at the current position.
	 */
	ZipReturn readZip64Record(EnhancedSeekableByteChannel esbc) throws IOException {
		zip64 = true;

		final long thisSignature = esbc.getInt();
		if (thisSignature != ZIP64ENDOFCENTRALDIRSIGNATURE)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		long tulong = esbc.getULong(); // Size of zip64 end of central directory record
		if (tulong != 44)
			return ZipReturn.ZIP64ENDOFCENTRALDIRERROR;

		esbc.getShort(); // version made by

		int tushort = esbc.getUShort(); // version needed to extract
		if (tushort != 45)
			return ZipReturn.ZIP64ENDOFCENTRALDIRERROR;

		long tuint = esbc.getUInt(); // number of this disk
		if (tuint != 0)
			return ZipReturn.ZIP64ENDOFCENTRALDIRERROR;

		tuint = esbc.getUInt(); // number of the disk with the start of the central directory
		if (tuint != 0)
			return ZipReturn.ZIP64ENDOFCENTRALDIRERROR;

		localFilesCount = esbc.getULong(); // total number of entries in the central directory on this disk

		tulong = esbc.getULong(); // total number of entries in the central directory
		if (tulong != localFilesCount)
			return ZipReturn.ZIP64ENDOFCENTRALDIRERROR;

		centerDirSize = esbc.getULong(); // size of central directory

		centerDirStart = esbc.getULong(); // offset of start of central directory with respect to the starting disk number

		return ZipReturn.ZIPGOOD;
	}

	/**
	 * Writes the zip64 EOCD record.
	 *
	 * @param entryCount
	 *            actual number of entries
	 */
	void writeZip64Record(EnhancedSeekableByteChannel esbc, int entryCount) throws IOException {
		esbc.putInt(ZIP64ENDOFCENTRALDIRSIGNATURE);
		esbc.putULong(44L); // Size of zip64 end of central directory record
		esbc.putUShort(45); // version made by
		esbc.putUShort(45); // version needed to extract
		esbc.putUInt(0); // number of this disk
		esbc.putUInt(0); // number of the disk with the start of the central directroy
		esbc.putULong(entryCount); // total number of entries in the central directory on this disk
		esbc.putULong(entryCount); // total number of entries in the central directory
		esbc.putULong(centerDirSize); // size of central directory
		esbc.putULong(centerDirStart); // offset of start of central directory with respect to the starting disk number
	}

	/**
	 * If the classic EOCD contained sentinel values, follows the zip64
	 * structures to obtain the true 64-bit values.
	 *
	 * @param endOfCentralDir
	 *            position of the classic EOCD signature
	 */
	ZipReturn readZip64StructuresIfNeeded(EnhancedSeekableByteChannel esbc, long endOfCentralDir) throws IOException {
		final boolean sizeSentinel = centerDirStart == 0xffffffffL || centerDirSize == 0xffffffffL;
		final boolean countSentinel = localFilesCount == 0xffff;
		if (sizeSentinel || countSentinel) {
			// the classic EOCD may legally store the literal count 65,535, so the
			// zip64 path is only taken when the locator is actually present
			if (countSentinel && !sizeSentinel && !hasZip64Locator(esbc, endOfCentralDir))
				return ZipReturn.ZIPGOOD;
			zip64 = true;
			esbc.position(endOfCentralDir - 20);
			ZipReturn zRet = readZip64Locator(esbc);
			if (zRet != ZipReturn.ZIPGOOD)
				return zRet;
			esbc.position(endOfCenterDir64);
			zRet = readZip64Record(esbc);
			if (zRet != ZipReturn.ZIPGOOD)
				return zRet;
		}
		return ZipReturn.ZIPGOOD;
	}

	private boolean hasZip64Locator(EnhancedSeekableByteChannel esbc, long endOfCentralDir) throws IOException {
		esbc.position(endOfCentralDir - 20);
		return esbc.getUInt() == ZIP64ENDOFCENTRALDIRECTORYLOCATOR;
	}

	/**
	 * Verifies whether the file comment indicates a valid torrentzip archive.
	 *
	 * <p>Side effect: advances and checksums over the central directory bytes
	 * via the provided channel.</p>
	 */
	boolean isTorrentZipped(EnhancedSeekableByteChannel esbc) throws IOException {
		if (fileComment.length == 22 && new String(fileComment, StandardCharsets.US_ASCII).substring(0, 14).equals("TORRENTZIPPED-")) //$NON-NLS-1$ //$NON-NLS-2$
		{
			final var buffer = new byte[(int) centerDirSize];
			esbc.position(centerDirStart);
			esbc.startChecksum();
			esbc.get(buffer);
			long r = esbc.endChecksum();
			final var tcrc = new String(fileComment, StandardCharsets.US_ASCII).substring(14, 22); //$NON-NLS-1$ //$NON-NLS-2$
			final var zcrc = HexFormat.of().withUpperCase().toHexDigits((int) r);
			if (tcrc.equals(zcrc))
				return true;
		}
		return false;
	}
}
