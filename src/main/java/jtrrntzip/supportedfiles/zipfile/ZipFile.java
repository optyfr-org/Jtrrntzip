package jtrrntzip.supportedfiles.zipfile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import jtrrntzip.Messages;
import jtrrntzip.TorrentZipCheck;
import jtrrntzip.ZipOpenType;
import jtrrntzip.ZipReturn;
import jtrrntzip.ZipStatus;
import jtrrntzip.supportedfiles.EnhancedSeekableByteChannel;
import jtrrntzip.supportedfiles.ICompress;

/**
 * A low level zip archive implementing the archive independent
 * {@link ICompress} contract.
 *
 * <p>Opening an archive locates the end of central directory record, follows
 * the zip64 structures when needed, parses the central directory and
 * verifies the local file headers. Torrentzip archives are recognized by
 * their {@code TORRENTZIPPED-XXXXXXXX} file comment: the checksum in the
 * comment is verified against the central directory and the entry order and
 * directory markers are validated, so an opened archive carries the
 * {@link ZipStatus#TRRNTZIP} state only when it fully follows the
 * format.</p>
 *
 * <p>Creating an archive applies the per entry torrentzip defaults: the fixed
 * torrentzip timestamp for every entry, maximum deflate level for the
 * entries written compressed and unchanged storage for raw entries. The
 * entry order itself is the caller's responsibility, the torrentzip rules
 * including the sorted names are validated by
 * {@link jtrrntzip.TorrentZipCheck} before the engine writes the entries.
 * The archive is finalized with the central directory, the zip64 structures
 * when its sizes require them and the {@code TORRENTZIPPED-XXXXXXXX} file
 * comment when every written entry was marked as torrentzip content.
 * Reading supports stored and deflated entries, the zip64 extras and the
 * unicode file name extras.</p>
 */
public final class ZipFile implements ICompress
{
	/**
	 * The signature of a central directory file header.
	 */
	static final int CENTRALDIRECTORYHEADERSIGNATURE = 0x02014b50;
	private static final int ENDOFCENTRALDIRSIGNATURE = 0x06054b50;
	/**
	 * The signature of a local file header.
	 */
	static final int LOCALFILEHEADERSIGNATURE = 0x04034b50;
	private static final int ZIP64ENDOFCENTRALDIRECTORYLOCATOR = 0x07064b50;
	private static final int ZIP64ENDOFCENTRALDIRSIGNATURE = 0x06064b50;

	/**
	 * Creates the parent directories of the file path when they do not exist
	 * yet.
	 */
	private static final void createDirForFile(File sFilename)
	{
		final File parent = sFilename.getParentFile();
		if (parent != null)
			parent.mkdirs();
	}

	/**
	 * Returns the localized explanation of the given result code.
	 *
	 * @param zS
	 *            the result code to explain
	 * @return the localized message, or the plain constant name when no
	 *         message exists for the code
	 */
	public static final String zipErrorMessageText(ZipReturn zS)
	{
		return switch (zS)
		{
			case ZIPGOOD -> Messages.getString("ZipFile.ZIPGood"); //$NON-NLS-1$
			case ZIPFILECOUNTERROR -> Messages.getString("ZipFile.ZIPFileCountError"); //$NON-NLS-1$
			case ZIPSIGNATUREERROR -> Messages.getString("ZipFile.ZipSignatureError"); //$NON-NLS-1$
			case ZIPEXTRADATAONENDOFZIP -> Messages.getString("ZipFile.ZipExtraDataOnEndOfZip"); //$NON-NLS-1$
			case ZIPUNSUPPORTEDCOMPRESSION -> Messages.getString("ZipFile.ZipUnsipportedCompression"); //$NON-NLS-1$
			case ZIPLOCALFILEHEADERERROR -> Messages.getString("ZipFile.ZipLocalFileHeaderError"); //$NON-NLS-1$
			case ZIPCENTRALDIRERROR -> Messages.getString("ZipFile.ZipCentralDirError"); //$NON-NLS-1$
			case ZIPREADINGFROMOUTPUTFILE -> Messages.getString("ZipFile.ZipReadingFromOutputFile"); //$NON-NLS-1$
			case ZIPWRITINGTOINPUTFILE -> Messages.getString("ZipFile.ZipWritingToInputFile"); //$NON-NLS-1$
			case ZIPERRORGETTINGDATASTREAM -> Messages.getString("ZipFile.ZipErrorGettingDataStream"); //$NON-NLS-1$
			case ZIPCRCDECODEERROR -> Messages.getString("ZipFile.ZIPCRCDecodeError"); //$NON-NLS-1$
			case ZIPDECODEERROR -> Messages.getString("ZipFile.ZipDecodeError"); //$NON-NLS-1$
			default -> zS.toString();
		};
	}

	/**
	 * Creates an unopened archive.
	 */
	public ZipFile() {
	}
	private long centerDirSize;
	private long centerDirStart;
	private long endOfCenterDir64;

	private EnhancedSeekableByteChannel esbc;
	private byte[] fileComment;
	private final List<LocalFile> localFiles = new ArrayList<>();

	private long localFilesCount;
	private EnumSet<ZipStatus> pZipStatus = EnumSet.noneOf(ZipStatus.class);

	private int readIndex;
	private boolean zip64;

	private File zipFileInfo = null;

	private final AtomicReference<Deflater> deflater = new AtomicReference<>();
	private final AtomicReference<Inflater> inflater = new AtomicReference<>();

	private ZipOpenType zipOpen = ZipOpenType.CLOSED;

	@Override
	/**
	 * Releases the deflater, the inflater and the underlying channel.
	 *
	 * <p>Entry streams that are still open when this runs are cut off; use
	 * {@link #zipFileClose()} to finalize an archive correctly.</p>
	 */
	public final void close()
	{
		if(deflater.get()!=null)
			deflater.get().end();
		if(inflater.get()!=null)
			inflater.get().end();
		if (esbc != null)
		{
			try
			{
				esbc.close();
			}
			catch (IOException _)
			{
                /* ignore */
			}
		}
	}

	@Override
	/**
	 * Returns the CRC-32 of the entry as the four little endian bytes stored
	 * in the archive.
	 *
	 * @param i
	 *            the index of the entry
	 * @return the CRC-32 as four little endian bytes
	 */
	public final byte[] crc32(int i)
	{
		return localFiles.get(i).getCrc();
	}

	/**
	 * Reads the end of central directory record at the current position and
	 * stores the entry count, the central directory bounds and the file
	 * comment.
	 */
	private final ZipReturn endOfCentralDirRead() throws IOException
	{
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

		if (esbc.position() != esbc.size())
			pZipStatus.add(ZipStatus.EXTRADATA);

		return ZipReturn.ZIPGOOD;
	}

	/**
	 * Writes the end of central directory record at the current position.
	 */
	private final void endOfCentralDirWrite() throws IOException
	{
		esbc.putUInt(ENDOFCENTRALDIRSIGNATURE);
		esbc.putUShort(0); // NumberOfThisDisk
		esbc.putUShort(0); // NumberOfThisDiskCenterDir
		// 65,535 entries is the largest count a classic EOCD can legally store,
		// the 0xffff sentinel written for larger counts resolves via the zip64 EOCD
		esbc.putUShort((localFiles.size() > 0xffff ? 0xffff : localFiles.size())); // TotalNumberOfEnteriesDisk
		esbc.putUShort((localFiles.size() > 0xffff ? 0xffff : localFiles.size())); // TotalNumber of entries in the central directory
		esbc.putUInt((centerDirSize >= 0xffffffffL ? 0xffffffffL : centerDirSize)); // SizeOfCenteralDir
		esbc.putUInt((centerDirStart >= 0xffffffffL ? 0xffffffffL : centerDirStart)); // Offset
		esbc.putUShort(fileComment.length);
		esbc.put(fileComment, 0, fileComment.length);
	}

	@Override
	/**
	 * Returns the decoded entry name of the entry at the given index.
	 *
	 * @param i
	 *            the index of the entry
	 * @return the entry name
	 */
	public final String filename(int i)
	{
		return localFiles.get(i).getFileName();
	}

	@Override
	/**
	 * Returns the stored read status of the entry at the given index.
	 *
	 * <p>This implementation never updates the status, it keeps
	 * {@link ZipReturn#ZIPUNTESTED} for every entry because the read
	 * integrity is verified while opening the archive.</p>
	 *
	 * @param i
	 *            the index of the entry
	 * @return the read status of the entry
	 */
	public final ZipReturn fileStatus(int i)
	{
		return localFiles.get(i).getFileStatus();
	}

	/**
	 * Searches the last 64 KiB of the file for the end of central directory
	 * signature and seeks to the position of the signature.
	 */
	private final ZipReturn findEndOfCentralDirSignature() throws IOException
	{
		long fileSize = esbc.size();

		var maxBackSearch = 0xffffL;

		if (esbc.size() < maxBackSearch)
			maxBackSearch = fileSize;

		final var buffSize = 0x400;

		final var buffer = new byte[buffSize + 4];

		long backPosition = 4;
		while (backPosition < maxBackSearch)
		{
			backPosition += buffSize;
			if (backPosition > maxBackSearch)
				backPosition = maxBackSearch;

			long readSize = backPosition > (buffSize + 4) ? (buffSize + 4) : backPosition;

			esbc.position(fileSize - backPosition);

			esbc.get(buffer, 0, (int) readSize);

			for (int i = (int) readSize - 4; i >= 0; i--)
			{
				if ((buffer[i] != 0x50) || (buffer[i + 1] != 0x4b) || (buffer[i + 2] != 0x05) || (buffer[i + 3] != 0x06))
					continue;

				esbc.position((fileSize - backPosition) + i);
				return ZipReturn.ZIPGOOD;
			}
		}
		return ZipReturn.ZIPCENTRALDIRERROR;
	}

	@Override
	/**
	 * Returns the number of entries parsed from the central directory.
	 *
	 * @return the entry count
	 */
	public final int localFilesCount()
	{
		return localFiles.size();
	}

	@Override
	/**
	 * Returns the modification time of the archive file in milliseconds.
	 *
	 * @return the file modification time, or 0 when no file is held
	 */
	public final long timeStamp()
	{
		return zipFileInfo != null ? zipFileInfo.lastModified() : 0;
	}

	@Override
	/**
	 * Returns the uncompressed size of the entry at the given index.
	 *
	 * @param i
	 *            the index of the entry
	 * @return the uncompressed size in bytes
	 */
	public final long uncompressedSize(int i)
	{
		return localFiles.get(i).getUncompressedSize();
	}

	/**
	 * Reads the zip64 end of central directory locator at the current
	 * position and stores the offset of the zip64 end of central directory
	 * record.
	 */
	private final ZipReturn zip64EndOfCentralDirectoryLocatorRead() throws IOException
	{
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
	 * Writes the zip64 end of central directory locator pointing at the
	 * stored zip64 end of central directory offset.
	 */
	private final void zip64EndOfCentralDirectoryLocatorWrite() throws IOException
	{
		esbc.putInt(ZIP64ENDOFCENTRALDIRECTORYLOCATOR);
		esbc.putUInt(0); // number of the disk with the start of the zip64 end of centeral directory
		esbc.putULong(endOfCenterDir64); // relative offset of the zip64 end of central directroy record
		esbc.putUInt(1); // total number of disks
	}

	/**
	 * Reads the zip64 end of central directory record at the current position
	 * and replaces the entry count and the central directory bounds with the
	 * 64-bit values.
	 */
	private final ZipReturn zip64EndOfCentralDirRead() throws IOException
	{
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
	 * Writes the zip64 end of central directory record at the current
	 * position.
	 */
	private final void zip64EndOfCentralDirWrite() throws IOException
	{
		esbc.putInt(ZIP64ENDOFCENTRALDIRSIGNATURE);
		esbc.putULong(44L); // Size of zip64 end of central directory record
		esbc.putUShort(45); // version made by
		esbc.putUShort(45); // version needed to extract
		esbc.putUInt(0); // number of this disk
		esbc.putUInt(0); // number of the disk with the start of the central directroy
		esbc.putULong(localFiles.size()); // total number of entries in the central directory on this disk
		esbc.putULong(localFiles.size()); // total number of entries in the central directory
		esbc.putULong(centerDirSize); // size of central directory
		esbc.putULong(centerDirStart); // offset of start of central directory with respect to the starting disk number
	}

	@Override
	/**
	 * Closes the archive, finalizing it first when it is open for writing.
	 *
	 * <p>For an archive open for writing this writes the central directory of
	 * all written entries, appends the {@code TORRENTZIPPED-XXXXXXXX} file
	 * comment with the checksum of the central directory when every entry
	 * was written in torrentzip mode, writes the zip64 structures when the
	 * archive sizes require them, truncates the file to its final size and
	 * closes the file. An archive open for reading is simply released.</p>
	 *
	 * @throws IOException
	 *             when writing the directory fails or closing fails
	 */
	public final void zipFileClose() throws IOException
	{
		switch (zipOpen)
		{
			case CLOSED ->
			{
			}
			case OPENREAD ->
			{
				close();
				zipOpen = ZipOpenType.CLOSED;
			}
			case OPENWRITE ->
			{
				zip64 = false;
				var lTrrntzip = true;

				centerDirStart = esbc.position();
				if (centerDirStart >= 0xffffffffL)
					zip64 = true;
				if (localFiles.size() > 0xffff)
					zip64 = true;

				esbc.startChecksum();
				for (final LocalFile t : localFiles)
				{
					t.centralDirectoryWrite(esbc);
					zip64 |= t.isZip64();
					lTrrntzip &= t.isTrrntZip();
				}

				centerDirSize = esbc.position() - centerDirStart;

				fileComment = lTrrntzip ? ("TORRENTZIPPED-" + HexFormat.of().withUpperCase().toHexDigits((int) esbc.endChecksum())).getBytes(StandardCharsets.US_ASCII) : new byte[0]; //$NON-NLS-1$
				pZipStatus = lTrrntzip ? EnumSet.of(ZipStatus.TRRNTZIP) : EnumSet.noneOf(ZipStatus.class);

				if (zip64)
				{
					endOfCenterDir64 = esbc.position();
					zip64EndOfCentralDirWrite();
					zip64EndOfCentralDirectoryLocatorWrite();
				}
				endOfCentralDirWrite();

				esbc.truncate(esbc.position());
				close();
				zipOpen = ZipOpenType.CLOSED;
			}
		}
	}

	@Override
	/**
	 * Abandons the archive after a failed operation.
	 *
	 * <p>An archive open for writing is closed and its file deleted so no half
	 * written archive remains; an archive open for reading is simply
	 * closed.</p>
	 *
	 * @throws IOException
	 *             when closing or deleting fails
	 */
	public final void zipFileCloseFailed() throws IOException
	{
		switch (zipOpen)
		{
			case CLOSED ->
			{
			}
			case OPENREAD ->
			{
				close();
				zipOpen = ZipOpenType.CLOSED;
			}
			case OPENWRITE ->
			{
				close();
				Files.deleteIfExists(zipFileInfo.toPath());
				zipFileInfo = null;
				zipOpen = ZipOpenType.CLOSED;
			}
		}
	}

	@Override
	/**
	 * Closes the entry read stream that was opened last.
	 *
	 * @return {@link ZipReturn#ZIPGOOD} when the stream was closed
	 * @throws IOException
	 *             when closing the entry fails
	 */
	public final ZipReturn zipFileCloseReadStream() throws IOException
	{
		return localFiles.get(readIndex).localFileCloseReadStream();
	}

	@Override
	/**
	 * Finishes the entry that was written last and records its CRC-32.
	 *
	 * @param crc32
	 *            the CRC-32 of the written entry data as four little endian
	 *            bytes
	 * @return {@link ZipReturn#ZIPGOOD} when the entry was finished
	 * @throws IOException
	 *             when finalizing the entry fails
	 */
	public final ZipReturn zipFileCloseWriteStream(byte[] crc32) throws IOException
	{
		return localFiles.getLast().localFileCloseWriteStream(crc32);
	}

	@Override
	/**
	 * Creates a new archive at the given path and opens it for writing.
	 *
	 * <p>Parent directories of the path are created when missing. Only one
	 * archive at a time can be held open by an instance.</p>
	 *
	 * @param newFilename
	 *            the archive file to create
	 * @return {@link ZipReturn#ZIPGOOD} when the archive was created,
	 *         {@link ZipReturn#ZIPFILEALREADYOPEN} when an archive is still
	 *         open
	 * @throws IOException
	 *             when creating or opening the file fails
	 */
	public final ZipReturn zipFileCreate(File newFilename) throws IOException
	{
		if (zipOpen != ZipOpenType.CLOSED)
			return ZipReturn.ZIPFILEALREADYOPEN;

		createDirForFile(newFilename);
		zipFileInfo = newFilename;

		esbc = new EnhancedSeekableByteChannel(Files.newByteChannel(newFilename.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ), ByteOrder.LITTLE_ENDIAN);
		zipOpen = ZipOpenType.OPENWRITE;
		return ZipReturn.ZIPGOOD;
	}

	@Override
	/**
	 * Returns the absolute path of the archive file.
	 *
	 * @return the absolute path, or an empty string when no file is held
	 */
	public final String zipFilename()
	{
		return zipFileInfo != null ? zipFileInfo.getAbsolutePath() : ""; //$NON-NLS-1$
	}

	@Override
	/**
	 * Opens the given archive for reading.
	 *
	 * <p>The file modification time is verified against the given timestamp,
	 * so an archive that changed since it was selected is rejected with
	 * {@link ZipReturn#ZIPERRORTIMESTAMP}. With {@code readHeaders} set, the
	 * end of central directory record is located and parsed, the zip64
	 * structures are followed when needed, the central directory and the
	 * local file headers are read and verified, and the torrentzip state is
	 * detected from the file comment checksum, the entry order and the
	 * directory markers.</p>
	 *
	 * @param newFilename
	 *            the archive file to open
	 * @param timestamp
	 *            the expected file modification time in milliseconds
	 * @param readHeaders
	 *            {@code true} to parse the archive structures right away
	 * @return {@link ZipReturn#ZIPGOOD} when the archive was opened,
	 *         otherwise the reason of the failure
	 * @throws IOException
	 *             when closing a failed open fails
	 */
	public final ZipReturn zipFileOpen(File newFilename, long timestamp, boolean readHeaders) throws IOException
	{
		zipFileClose();
		pZipStatus = EnumSet.noneOf(ZipStatus.class);
		zip64 = false;
		centerDirStart = 0;
		centerDirSize = 0;
		zipFileInfo = null;

		try
		{
			if (!newFilename.exists())
			{
				zipFileClose();
				return ZipReturn.ZIPERRORFILENOTFOUND;
			}
			zipFileInfo = newFilename;
			if (zipFileInfo.lastModified() != timestamp)
			{
				zipFileClose();
				return ZipReturn.ZIPERRORTIMESTAMP;
			}
			esbc = new EnhancedSeekableByteChannel(Files.newByteChannel(newFilename.toPath(), StandardOpenOption.READ), ByteOrder.LITTLE_ENDIAN);
		}
		catch (IOException _)
		{
			zipFileClose();
			return ZipReturn.ZIPERROROPENINGFILE;
		}
		zipOpen = ZipOpenType.OPENREAD;

		if (!readHeaders)
			return ZipReturn.ZIPGOOD;

		try
		{
			ZipReturn zRet = findEndOfCentralDirSignature();
			if (zRet != ZipReturn.ZIPGOOD)
				return fail(zRet);

			long endOfCentralDir = esbc.position();
			zRet = endOfCentralDirRead();
			if (zRet != ZipReturn.ZIPGOOD)
				return fail(zRet);

			zRet = readZip64StructuresIfNeeded(endOfCentralDir);
			if (zRet != ZipReturn.ZIPGOOD)
				return fail(zRet);

			boolean trrntzip = isTorrentZipped();

			zRet = readCentralDirectoryEntries();
			if (zRet != ZipReturn.ZIPGOOD)
				return fail(zRet);

			final var scan = readLocalFileHeaders(trrntzip);
			if (scan.status() != ZipReturn.ZIPGOOD)
				return fail(scan.status());
			trrntzip = scan.trrntZip();
			if (trrntzip)
				trrntzip = isTorrentZipFileOrderValid();

			if (trrntzip)
				trrntzip = hasNoUnnecessaryDirectoryEntries();

			if (trrntzip)
				pZipStatus.add(ZipStatus.TRRNTZIP);

			return ZipReturn.ZIPGOOD;
		}
		catch (Exception _)
		{
			zipFileClose();
			return ZipReturn.ZIPERRORREADINGFILE;
		}

	}

	/**
	 * Tells if the entries are in strict torrentzip sort order.
	 */
	private final boolean isTorrentZipFileOrderValid()
	{
		for (var i = 0; i < localFilesCount - 1; i++)
		{
			if (TorrentZipCheck.trrntZipStringCompare(localFiles.get(i).getFileName(), localFiles.get(i + 1).getFileName()) >= 0)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Tells if no directory marker entry is directly followed by an entry
	 * inside that directory.
	 */
	private final boolean hasNoUnnecessaryDirectoryEntries()
	{
		for (var i = 0; i < localFilesCount - 1; i++)
		{
			if (TorrentZipCheck.isUnnecessaryDirectoryEntry(localFiles.get(i).getFileName(), localFiles.get(i + 1).getFileName()))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Follows the zip64 structures when the classic record stores sentinel
	 * values.
	 *
	 * <p>The count sentinel 0xffff can legally be a literal count, so the
	 * zip64 path is taken only when a size sentinel stored by the classic
	 * record or an actually present zip64 locator requires it.</p>
	 */
	private final ZipReturn readZip64StructuresIfNeeded(long endOfCentralDir) throws IOException
	{
		final boolean sizeSentinel = centerDirStart == 0xffffffffL || centerDirSize == 0xffffffffL;
		final boolean countSentinel = localFilesCount == 0xffff;
		if (sizeSentinel || countSentinel)
		{
			// the classic EOCD may legally store the literal count 65,535, so the
			// zip64 path is only taken when the locator is actually present
			if (countSentinel && !sizeSentinel && !hasZip64Locator(endOfCentralDir))
				return ZipReturn.ZIPGOOD;
			zip64 = true;
			esbc.position(endOfCentralDir - 20);
			ZipReturn zRet = zip64EndOfCentralDirectoryLocatorRead();
			if (zRet != ZipReturn.ZIPGOOD)
				return zRet;
			esbc.position(endOfCenterDir64);
			zRet = zip64EndOfCentralDirRead();
			if (zRet != ZipReturn.ZIPGOOD)
				return zRet;
		}
		return ZipReturn.ZIPGOOD;
	}

	/**
	 * Tells if a zip64 locator sits directly in front of the end of central
	 * directory record.
	 */
	private final boolean hasZip64Locator(long endOfCentralDir) throws IOException
	{
		esbc.position(endOfCentralDir - 20);
		return esbc.getUInt() == ZIP64ENDOFCENTRALDIRECTORYLOCATOR;
	}

	/**
	 * Verifies the torrentzip file comment against the central directory.
	 *
	 * <p>The comment must be {@code TORRENTZIPPED-XXXXXXXX} and its hex
	 * value must equal the CRC-32 of the central directory bytes.</p>
	 */
	private final boolean isTorrentZipped() throws IOException
	{
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

	/**
	 * Reads the central directory entries between the stored bounds.
	 */
	private final ZipReturn readCentralDirectoryEntries() throws IOException
	{
		esbc.position(centerDirStart);
		localFiles.clear();
		for (var i = 0; i < localFilesCount; i++)
		{
			final var lc = new LocalFile(esbc);
			ZipReturn zRet = lc.centralDirectoryRead();
			if (zRet != ZipReturn.ZIPGOOD)
			{
				lc.close();
				return zRet;
			}
			zip64 |= lc.isZip64();
			localFiles.add(lc);
		}
		return ZipReturn.ZIPGOOD;
	}

	/**
	 * The status of the local file header scan together with the torrentzip
	 * state that survived the scan.
	 */
	private record LocalHeaderScan(ZipReturn status, boolean trrntZip)
	{
	}

	/**
	 * Reads and verifies the local file header of every entry, updating the
	 * torrentzip state of the entries along the way.
	 */
	private final LocalHeaderScan readLocalFileHeaders(boolean trrntzip) throws IOException
	{
		for (var i = 0; i < localFilesCount; i++)
		{
			ZipReturn zRet = localFiles.get(i).localFileHeaderRead();
			if (zRet != ZipReturn.ZIPGOOD)
				return new LocalHeaderScan(zRet, trrntzip);
			trrntzip &= localFiles.get(i).isTrrntZip();
		}
		return new LocalHeaderScan(ZipReturn.ZIPGOOD, trrntzip);
	}

	/**
	 * Closes the archive and returns the failure code.
	 */
	private final ZipReturn fail(ZipReturn r) throws IOException
	{
		zipFileClose();
		return r;
	}

	@Override
	/**
	 * Opens the entry at the given index for reading.
	 *
	 * <p>Stored and deflated entries are supported; deflated entries deliver
	 * either the inflated data or, with {@code raw} set, the stored
	 * compressed bytes. Opening re-reads the local file header of the entry,
	 * and a failed re-read closes the whole archive.</p>
	 *
	 * @param index
	 *            the index of the entry to read
	 * @param raw
	 *            {@code true} to return the stored compressed bytes of a
	 *            deflated entry unchanged
	 * @return the opened stream with its expected size, failed when the
	 *         archive is not open for reading or the entry cannot be read
	 * @throws IOException
	 *             when reading the entry header fails
	 */
	public final ICompress.OpenedReadStream zipFileOpenReadStream(int index, boolean raw) throws IOException
	{
		readIndex = index;
		if (zipOpen != ZipOpenType.OPENREAD)
			return ICompress.OpenedReadStream.failed(ZipReturn.ZIPREADINGFROMOUTPUTFILE);

		final var localFile = localFiles.get(index);
		ZipReturn zRet = localFile.localFileHeaderRead();
		if (zRet != ZipReturn.ZIPGOOD)
		{
			zipFileClose();
			return ICompress.OpenedReadStream.failed(zRet);
		}

		return localFile.localFileOpenReadStream(raw, inflater);
	}

	@Override
	/**
	 * Appends a new entry to the archive that is open for writing.
	 *
	 * @param raw
	 *            {@code true} to write the data unchanged as stored bytes
	 * @param trrntzip
	 *            {@code true} when the entry is written in torrentzip mode
	 * @param filename
	 *            the entry path inside the archive
	 * @param uncompressedSize
	 *            the expected total number of bytes to be written
	 * @param compressionMethod
	 *            the compression method of the entry, 8 for deflate
	 * @return the opened write stream, failed when the archive is not open
	 *         for writing
	 * @throws IOException
	 *             when writing the entry header fails
	 */
	public final ICompress.OpenedWriteStream zipFileOpenWriteStream(boolean raw, boolean trrntzip, String filename, long uncompressedSize, short compressionMethod) throws IOException
	{
		if (zipOpen != ZipOpenType.OPENWRITE)
			return ICompress.OpenedWriteStream.failed(ZipReturn.ZIPWRITINGTOINPUTFILE);

		final var lf = new LocalFile(esbc, filename);

		final var retVal = lf.localFileOpenWriteStream(raw, trrntzip, uncompressedSize, compressionMethod, deflater);

		localFiles.add(lf);

		return retVal;
	}

	@Override
	/**
	 * Returns the current open state of the archive.
	 *
	 * @return the open state
	 */
	public final ZipOpenType zipOpen()
	{
		return zipOpen;
	}

	@Override
	/**
	 * Returns the extra states observed while opening the archive, for
	 * example {@link ZipStatus#TRRNTZIP} for a fully valid torrentzip and
	 * {@link ZipStatus#EXTRADATA} for trailing bytes after the end of
	 * central directory record.
	 *
	 * @return the observed states, empty for a plain archive
	 */
	public final EnumSet<ZipStatus> zipStatus()
	{
		return pZipStatus;
	}

}
