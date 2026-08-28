package jtrrntzip.supportedfiles.zipfile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import jtrrntzip.Messages;
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
		sFilename.getParentFile().mkdirs();
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

	private BigInteger centerDirSize;
	private BigInteger centerDirStart;
	private BigInteger endOfCenterDir64;

	private EnhancedSeekableByteChannel esbc;
	private byte[] fileComment;
	private final List<LocalFile> localFiles = new ArrayList<>();

	private BigInteger localFilesCount;
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
			catch (IOException e)
			{
				System.err.println(e.getMessage());
			}
		}
	}

	@Override
	public final byte[] crc32(int i)
	{
		return localFiles.get(i).getCrc();
	}

	@Override
	public final void deepScan()
	{
		for (LocalFile lfile : localFiles)
			lfile.localFileCheck();
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

		localFilesCount = BigInteger.valueOf(esbc.getUShort()); // TotalNumberOfEnteriesDisk

		tushort = esbc.getUShort(); // TotalNumber of entries in the central directory
		if (BigInteger.valueOf(tushort).compareTo(localFilesCount) != 0)
			return ZipReturn.ZIPENDOFCENTRALDIRECTORYERROR;

		centerDirSize = BigInteger.valueOf(esbc.getUInt()); // SizeOfCenteralDir
		centerDirStart = BigInteger.valueOf(esbc.getUInt()); // Offset

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
		esbc.putUShort((localFiles.size() >= 0xffff ? 0xffff : localFiles.size())); // TotalNumberOfEnteriesDisk
		esbc.putUShort((localFiles.size() >= 0xffff ? 0xffff : localFiles.size())); // TotalNumber of entries in the central directory
		esbc.putUInt((centerDirSize.compareTo(BigInteger.valueOf(0xffffffffL)) >= 0 ? 0xffffffffL : centerDirSize).longValue());
		esbc.putUInt((centerDirStart.compareTo(BigInteger.valueOf(0xffffffffL)) >= 0 ? 0xffffffffL : centerDirStart).longValue());
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
	public final BigInteger localHeader(int i)
	{
		return ((localFiles.get(i).getGeneralPurposeBitFlag() & 8) == 0) ? localFiles.get(i).getRelativeOffsetOfLocalHeader() : null;
	}

	@Override
	public final long timeStamp()
	{
		return zipFileInfo != null ? zipFileInfo.lastModified() : 0;
	}

	@Override
	public final BigInteger uncompressedSize(int i)
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
		
		BigInteger tulong = esbc.getULong(); // Size of zip64 end of central directory record
		if (tulong.compareTo(BigInteger.valueOf(44)) != 0)
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
		if (tulong.compareTo(localFilesCount)!=0)
			return ZipReturn.ZIP64ENDOFCENTRALDIRERROR;

		centerDirSize = esbc.getULong(); // size of central directory

		centerDirStart = esbc.getULong(); // offset of start of central directory with respect to the starting disk number

		return ZipReturn.ZIPGOOD;
	}

	private final void zip64EndOfCentralDirWrite() throws IOException
	{
		esbc.putInt(ZIP64ENDOFCENTRALDIRSIGNATURE);
		esbc.putULong(BigInteger.valueOf(44L)); // Size of zip64 end of central directory record
		esbc.putUShort(45); // version made by
		esbc.putUShort(45); // version needed to extract
		esbc.putUInt(0); // number of this disk
		esbc.putUInt(0); // number of the disk with the start of the central directroy
		esbc.putULong(BigInteger.valueOf(localFiles.size())); // total number of entries in the central directory on this disk
		esbc.putULong(BigInteger.valueOf(localFiles.size())); // total number of entries in the central directory
		esbc.putULong(centerDirSize); // size of central directory
		esbc.putULong(centerDirStart); // offset of start of central directory with respect to the starting disk number
	}

	@Override
	public final void zipFileAddDirectory() throws IOException
	{
		localFiles.get(localFiles.size() - 1).localFileAddDirectory();
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

		centerDirStart = BigInteger.valueOf(esbc.position());
		if (centerDirStart.compareTo(BigInteger.valueOf(0xffffffffL)) >= 0)
			zip64 = true;

		esbc.startChecksum();
		for (final LocalFile t : localFiles)
		{
			t.centeralDirectoryWrite(esbc);
			zip64 |= t.isZip64();
			lTrrntzip &= t.isTrrntZip();
		}

		centerDirSize = BigInteger.valueOf(esbc.position() - centerDirStart.longValue());

		fileComment = lTrrntzip ? String.format("TORRENTZIPPED-%08X", esbc.endChecksum()).getBytes(StandardCharsets.US_ASCII) : new byte[0]; //$NON-NLS-1$ //$NON-NLS-2$
		pZipStatus = lTrrntzip ? EnumSet.of(ZipStatus.TRRNTZIP) : EnumSet.noneOf(ZipStatus.class);

		if (zip64)
		{
			endOfCenterDir64 = BigInteger.valueOf(esbc.position());
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
		centerDirStart = BigInteger.valueOf(0);
		centerDirSize = BigInteger.valueOf(0);
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
		catch (IOException e)
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
			{
				zipFileClose();
				return zRet;
			}

			long endOfCentralDir = esbc.position();
			zRet = endOfCentralDirRead();
			if (zRet != ZipReturn.ZIPGOOD)
			{
				zipFileClose();
				return zRet;
			}

			// check if this is a ZIP64 zip and if it is read the Zip64 End Of
			// Central Dir Info
			if (centerDirStart.compareTo(BigInteger.valueOf(0xffffffffL)) == 0 || centerDirSize.compareTo(BigInteger.valueOf(0xffffffffL)) == 0 || localFilesCount.compareTo(BigInteger.valueOf(0xffff)) == 0)
			{
				zip64 = true;
				esbc.position(endOfCentralDir - 20);
				zRet = zip64EndOfCentralDirectoryLocatorRead();
				if (zRet != ZipReturn.ZIPGOOD)
				{
					zipFileClose();
					return zRet;
				}
				esbc.position(endOfCenterDir64.longValue());
				zRet = zip64EndOfCentralDirRead();
				if (zRet != ZipReturn.ZIPGOOD)
				{
					zipFileClose();
					return zRet;
				}
			}

			var trrntzip = false;

			// check if the ZIP has a valid TorrentZip file comment
			if (fileComment.length == 22 && new String(fileComment, StandardCharsets.US_ASCII).substring(0, 14).equals("TORRENTZIPPED-")) //$NON-NLS-1$ //$NON-NLS-2$
			{
				final var buffer = new byte[centerDirSize.intValue()];
				esbc.position(centerDirStart.longValue());
				esbc.startChecksum();
				esbc.get(buffer);
				long r = esbc.endChecksum();

				final var tcrc = new String(fileComment, StandardCharsets.US_ASCII).substring(14, 22); //$NON-NLS-1$
				final var zcrc = String.format("%08X", r); //$NON-NLS-1$
				if (tcrc.equalsIgnoreCase(zcrc))
					trrntzip = true;
			}

			// now read the central directory
			esbc.position(centerDirStart.longValue());

			localFiles.clear();
			for (var i = 0; i < localFilesCount.longValue(); i++)
			{
				final var lc = new LocalFile(esbc);
				zRet = lc.centralDirectoryRead();
				if (zRet != ZipReturn.ZIPGOOD)
				{
					zipFileClose();
					lc.close();
					return zRet;
				}
				zip64 |= lc.isZip64();
				localFiles.add(lc);
			}

			for (var i = 0; i < localFilesCount.intValue(); i++)
			{
				zRet = localFiles.get(i).localFileHeaderRead();
				if (zRet != ZipReturn.ZIPGOOD)
				{
					zipFileClose();
					return zRet;
				}
				trrntzip &= localFiles.get(i).isTrrntZip();
			}

			// check trrntzip file order
			if (trrntzip)
				for (var i = 0; i < localFilesCount.intValue() - 1; i++)
				{
					if (localFiles.get(i).getFileName().compareToIgnoreCase(localFiles.get(i + 1).getFileName()) >= 0)
					{
						trrntzip = false;
						break;
					}
				}

			// check trrntzip directories
			if (trrntzip)
				for (var i = 0; i < localFilesCount.intValue() - 1; i++)
				{
					// see if we found a directory
					String filename0 = localFiles.get(i).getFileName();
					if (filename0.charAt(filename0.length() - 1) != '/')
						continue;

					// see if the next file is in that directory
					String filename1 = localFiles.get(i + 1).getFileName();
					if (filename1.length() <= filename0.length())
						continue;
					if (filename0.compareToIgnoreCase(filename1.substring(0, filename0.length())) != 0)
						continue;

					// if we found a file in the directory then we do not need
					// the directory entry
					trrntzip = false;
					break;
				}

			if (trrntzip)
				pZipStatus.add(ZipStatus.TRRNTZIP);

			return ZipReturn.ZIPGOOD;
		}
		catch (Exception e)
		{
			System.err.println(e.getMessage());
			zipFileClose();
			return ZipReturn.ZIPERRORREADINGFILE;
		}

	}

	public final ZipReturn zipFileOpenReadStream(int index, boolean raw, AtomicReference<InputStream> stream, AtomicReference<BigInteger> streamSize, AtomicInteger compressionMethod) throws IOException
	{
		streamSize.set(BigInteger.valueOf(0));
		compressionMethod.set(0);
		readIndex = index;
		stream.set(null);
		if (zipOpen != ZipOpenType.OPENREAD)
			return ZipReturn.ZIPREADINGFROMOUTPUTFILE;

		ZipReturn zRet = localFiles.get(index).localFileHeaderRead();
		if (zRet != ZipReturn.ZIPGOOD)
		{
			zipFileClose();
			return zRet;
		}

		return localFiles.get(index).localFileOpenReadStream(raw, stream, streamSize, compressionMethod, inflater);
	}

	public final ZipReturn zipFileOpenReadStreamQuick(BigInteger pos, boolean raw, AtomicReference<InputStream> stream, AtomicReference<BigInteger> streamSize, AtomicInteger compressionMethod) throws IOException
	{
		final var tmpFile = new LocalFile(esbc);
		tmpFile.localFilePos(pos);

		localFiles.clear();
		localFiles.add(tmpFile);
		ZipReturn zr = tmpFile.localFileHeaderReadQuick();
		if (zr != ZipReturn.ZIPGOOD)
		{
			stream.set(null);
			streamSize.set(BigInteger.valueOf(0));
			compressionMethod.set(0);
			return zr;
		}
		readIndex = 0;

		return tmpFile.localFileOpenReadStream(raw, stream, streamSize, compressionMethod, inflater);
	}

	@Override
	public final ZipReturn zipFileOpenWriteStream(boolean raw, boolean trrntzip, String filename, BigInteger uncompressedSize, short compressionMethod, AtomicReference<OutputStream> stream) throws IOException
	{
		stream.set(null);
		if (zipOpen != ZipOpenType.OPENWRITE)
			return ZipReturn.ZIPWRITINGTOINPUTFILE;

		final var lf = new LocalFile(esbc, filename);

		ZipReturn retVal = lf.localFileOpenWriteStream(raw, trrntzip, uncompressedSize, compressionMethod, stream, deflater);

		localFiles.add(lf);

		return retVal;
	}

	@Override
	public final ZipReturn zipFileRollBack() throws IOException
	{
		if (zipOpen != ZipOpenType.OPENWRITE)
			return ZipReturn.ZIPWRITINGTOINPUTFILE;

		int fileCount = localFiles.size();
		if (fileCount == 0)
			return ZipReturn.ZIPERRORROLLBACKFILE;

		final var lf = localFiles.get(fileCount - 1);

		localFiles.remove(fileCount - 1);
		esbc.position(lf.localFilePos().longValueExact());
		return ZipReturn.ZIPGOOD;
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
