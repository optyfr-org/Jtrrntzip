package jtrrntzip.supportedfiles.zipfile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
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

public final class ZipFile implements ICompress
{
	static final int CENTRALDIRECTORYHEADERSIGNATURE = 0x02014b50;
	private static final int ENDOFCENTRALDIRSIGNATURE = 0x06054b50;
	static final int LOCALFILEHEADERSIGNATURE = 0x04034b50;
	private static final int ZIP64ENDOFCENTRALDIRECTORYLOCATOR = 0x07064b50;
	private static final int ZIP64ENDOFCENTRALDIRSIGNATURE = 0x06064b50;

	private static final void createDirForFile(File sFilename)
	{
		final File parent = sFilename.getParentFile();
		if (parent != null)
			parent.mkdirs();
	}

	public static final String zipErrorMessageText(ZipReturn zS)
	{
		final String ret;
		switch (zS)
		{
			case ZIPGOOD:
				ret = Messages.getString("ZipFile.ZIPGood"); //$NON-NLS-1$
				break;
			case ZIPFILECOUNTERROR:
				ret = Messages.getString("ZipFile.ZIPFileCountError"); //$NON-NLS-1$
				break;
			case ZIPSIGNATUREERROR:
				ret = Messages.getString("ZipFile.ZipSignatureError"); //$NON-NLS-1$
				break;
			case ZIPEXTRADATAONENDOFZIP:
				ret = Messages.getString("ZipFile.ZipExtraDataOnEndOfZip"); //$NON-NLS-1$
				break;
			case ZIPUNSUPPORTEDCOMPRESSION:
				ret = Messages.getString("ZipFile.ZipUnsipportedCompression"); //$NON-NLS-1$
				break;
			case ZIPLOCALFILEHEADERERROR:
				ret = Messages.getString("ZipFile.ZipLocalFileHeaderError"); //$NON-NLS-1$
				break;
			case ZIPCENTRALDIRERROR:
				ret = Messages.getString("ZipFile.ZipCentralDirError"); //$NON-NLS-1$
				break;
			case ZIPREADINGFROMOUTPUTFILE:
				ret = Messages.getString("ZipFile.ZipReadingFromOutputFile"); //$NON-NLS-1$
				break;
			case ZIPWRITINGTOINPUTFILE:
				ret = Messages.getString("ZipFile.ZipWritingToInputFile"); //$NON-NLS-1$
				break;
			case ZIPERRORGETTINGDATASTREAM:
				ret = Messages.getString("ZipFile.ZipErrorGettingDataStream"); //$NON-NLS-1$
				break;
			case ZIPCRCDECODEERROR:
				ret = Messages.getString("ZipFile.ZipCRCDecodeError"); //$NON-NLS-1$
				break;
			case ZIPDECODEERROR:
				ret = Messages.getString("ZipFile.ZipDecodeError"); //$NON-NLS-1$
				break;
			default:
				ret = zS.toString();
				break;
		}
		return ret;
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
	public final byte[] crc32(int i)
	{
		return localFiles.get(i).getCrc();
	}

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
	public final String filename(int i)
	{
		return localFiles.get(i).getFileName();
	}

	@Override
	public final ZipReturn fileStatus(int i)
	{
		return localFiles.get(i).getFileStatus();
	}

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
	public final int localFilesCount()
	{
		return localFiles.size();
	}

	@Override
	public final long timeStamp()
	{
		return zipFileInfo != null ? zipFileInfo.lastModified() : 0;
	}

	@Override
	public final long uncompressedSize(int i)
	{
		return localFiles.get(i).getUncompressedSize();
	}

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

	private final void zip64EndOfCentralDirectoryLocatorWrite() throws IOException
	{
		esbc.putInt(ZIP64ENDOFCENTRALDIRECTORYLOCATOR);
		esbc.putUInt(0); // number of the disk with the start of the zip64 end of centeral directory
		esbc.putULong(endOfCenterDir64); // relative offset of the zip64 end of central directroy record
		esbc.putUInt(1); // total number of disks
	}

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
	public final void zipFileClose() throws IOException
	{
		if (zipOpen == ZipOpenType.CLOSED)
			return;

		if (zipOpen == ZipOpenType.OPENREAD)
		{
			close();
			zipOpen = ZipOpenType.CLOSED;
			return;
		}

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

		fileComment = lTrrntzip ? String.format("TORRENTZIPPED-%08X", esbc.endChecksum()).getBytes(StandardCharsets.US_ASCII) : new byte[0]; //$NON-NLS-1$ //$NON-NLS-2$
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

	@Override
	public final void zipFileCloseFailed() throws IOException
	{
		if (zipOpen == ZipOpenType.CLOSED)
			return;

		if (zipOpen == ZipOpenType.OPENREAD)
		{
			close();
			zipOpen = ZipOpenType.CLOSED;
			return;
		}

		close();
		Files.deleteIfExists(zipFileInfo.toPath());
		zipFileInfo = null;
		zipOpen = ZipOpenType.CLOSED;
	}

	@Override
	public final ZipReturn zipFileCloseReadStream() throws IOException
	{
		return localFiles.get(readIndex).localFileCloseReadStream();
	}

	@Override
	public final ZipReturn zipFileCloseWriteStream(byte[] crc32) throws IOException
	{
		return localFiles.get(localFiles.size() - 1).localFileCloseWriteStream(crc32);
	}

	@Override
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
	public final String zipFilename()
	{
		return zipFileInfo != null ? zipFileInfo.getAbsolutePath() : ""; //$NON-NLS-1$
	}

	@Override
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

	private final boolean hasZip64Locator(long endOfCentralDir) throws IOException
	{
		esbc.position(endOfCentralDir - 20);
		return esbc.getUInt() == ZIP64ENDOFCENTRALDIRECTORYLOCATOR;
	}

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
			final var zcrc = String.format("%08X", r); //$NON-NLS-1$
			if (tcrc.equals(zcrc))
				return true;
		}
		return false;
	}

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

	private record LocalHeaderScan(ZipReturn status, boolean trrntZip)
	{
	}

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

	private final ZipReturn fail(ZipReturn r) throws IOException
	{
		zipFileClose();
		return r;
	}

	@Override
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
	public final ZipOpenType zipOpen()
	{
		return zipOpen;
	}

	@Override
	public final EnumSet<ZipStatus> zipStatus()
	{
		return pZipStatus;
	}

}
