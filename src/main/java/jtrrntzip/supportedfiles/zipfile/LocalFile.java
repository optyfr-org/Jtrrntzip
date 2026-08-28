package jtrrntzip.supportedfiles.zipfile;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Checksum;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.apache.commons.io.input.BoundedInputStream;

import jtrrntzip.ZipReturn;
import jtrrntzip.supportedfiles.EnhancedSeekableByteChannel;
import jtrrntzip.supportedfiles.UnsignedTypes;

public final class LocalFile implements Closeable, AutoCloseable
{
	private BigInteger compressedSize;

	private int compressionMethod;
	private long crc32Location;
	private long dataLocation;
	private long extraLocation;
	private int generalPurposeBitFlag;
	private int lastModFileDate;
	private int lastModFileTime;
	private InputStream readStream;
	private boolean trrntZip;

	private BigInteger uncompressedSize;
	private OutputStream writeStream;
	private byte[] crc;

	private final EnhancedSeekableByteChannel esbc;

	private String fileName;

	private ZipReturn fileStatus = ZipReturn.ZIPUNTESTED;
	

	private BigInteger relativeOffsetOfLocalHeader; // only in central directory

	private boolean zip64;

	public LocalFile(EnhancedSeekableByteChannel esbc)
	{
		this.esbc = esbc;
	}

	public LocalFile(EnhancedSeekableByteChannel esbc, String filename)
	{
		zip64 = false;
		this.esbc = esbc;
		generalPurposeBitFlag = 2; // Maximum Compression Deflating
		compressionMethod = 8; // Compression Method Deflate
		lastModFileTime = 48128;
		lastModFileDate = 8600;

		fileName = filename;
	}

	public final void centeralDirectoryWrite(EnhancedSeekableByteChannel esbc) throws IOException
	{

		final var header = 0x02014B50;

		List<Byte> extraField = new ArrayList<>();

		long cdUncompressedSize;
		if (getUncompressedSize().compareTo(BigInteger.valueOf(0xffffffffL)) >= 0)
		{
			zip64 = true;
			cdUncompressedSize = 0xffffffffL;
			for (byte b : ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(UnsignedTypes.fromULong(getUncompressedSize())).array())
				extraField.add(b);
		}
		else
			cdUncompressedSize = UnsignedTypes.fromULong(getUncompressedSize());

		long cdCompressedSize;
		if (compressedSize.compareTo(BigInteger.valueOf(0xffffffffL)) >= 0)
		{
			zip64 = true;
			cdCompressedSize = 0xffffffffL;
			for (byte b : ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(UnsignedTypes.fromULong(compressedSize)).array())
				extraField.add(b);
		}
		else
			cdCompressedSize = UnsignedTypes.fromULong(compressedSize);

		long cdRelativeOffsetOfLocalHeader;
		if (getRelativeOffsetOfLocalHeader().compareTo(BigInteger.valueOf(0xffffffffL)) >= 0)
		{
			zip64 = true;
			cdRelativeOffsetOfLocalHeader = 0xffffffffL;
			for (byte b : ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(UnsignedTypes.fromULong(getRelativeOffsetOfLocalHeader())).array())
				extraField.add(b);
		}
		else
			cdRelativeOffsetOfLocalHeader = UnsignedTypes.fromULong(getRelativeOffsetOfLocalHeader());

		if (!extraField.isEmpty())
		{
			int exfl = extraField.size();
			var i = 0;
			for (byte b : ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(UnsignedTypes.fromUShort(0x0001)).array())
				extraField.add(i++, b);
			for (byte b : ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(UnsignedTypes.fromUShort(exfl)).array())
				extraField.add(i++, b);
		}
		int extraFieldLength = extraField.size();

		byte[] bFileName;

		if (!StandardCharsets.US_ASCII.newEncoder().canEncode(getFileName())) //$NON-NLS-1$
		{
			generalPurposeBitFlag = getGeneralPurposeBitFlag() | 1 << 11;
			bFileName = getFileName().getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
		}
		else
			bFileName = getFileName().getBytes(StandardCharsets.US_ASCII); //$NON-NLS-1$
		int fileNameLength = bFileName.length;

		int versionNeededToExtract = (isZip64() ? 45 : 20);

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
		esbc.putUShort(fileNameLength); // 30
		esbc.putUShort(extraFieldLength); // 32
		esbc.putUShort(0); // 34 // file comment length
		esbc.putUShort(0); // 36 // disk number start
		esbc.putUShort(0); // 38 // internal file attributes
		esbc.putUInt(0); // 42 // external file attributes
		esbc.putUInt(cdRelativeOffsetOfLocalHeader); // 46
		esbc.put(bFileName);
		for (byte b : extraField)
			esbc.put(b);
		// No File Comment
	}

	public final ZipReturn centralDirectoryRead()
	{
		try
		{

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

			compressedSize = BigInteger.valueOf(esbc.getUInt());
			uncompressedSize = BigInteger.valueOf(esbc.getUInt());

			int fileNameLength = esbc.getUShort();
			int extraFieldLength = esbc.getUShort();
			int fileCommentLength = esbc.getUShort();

			esbc.getUShort(); // diskNumberStart
			esbc.getUShort(); // internalFileAttributes
			esbc.getUInt(); // externalFileAttributes

			relativeOffsetOfLocalHeader = BigInteger.valueOf(esbc.getUInt());

			final var bFileName = new byte[fileNameLength];
			esbc.get(bFileName);
			fileName = (getGeneralPurposeBitFlag() & (1 << 11)) == 0 ? new String(bFileName, StandardCharsets.US_ASCII) : new String(bFileName, StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

			final var extraField = new byte[extraFieldLength];
			esbc.get(extraField);

			esbc.position(esbc.position() + fileCommentLength); // File
																// Comments

			ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
			while (extraFieldLength > bb.position())
			{
				int type = UnsignedTypes.toUShort(bb.getShort());
				int blockLength = UnsignedTypes.toUShort(bb.getShort());
				switch (type)
				{
					case 0x0001:
						zip64 = true;
						if (getUncompressedSize().longValue() == 0xffffffffL)
							uncompressedSize = UnsignedTypes.toULong(bb.getLong());
						if (compressedSize.longValue() == 0xffffffffL)
							compressedSize = UnsignedTypes.toULong(bb.getLong());
						if (getRelativeOffsetOfLocalHeader().longValue() == 0xffffffffL)
							relativeOffsetOfLocalHeader = UnsignedTypes.toULong(bb.getLong());
						break;
					case 0x7075:
					{
						@SuppressWarnings("unused")
						final byte version = bb.get();
						final long nameCRC32 = UnsignedTypes.toUInt(bb.getInt());

						final var crcTest = new java.util.zip.CRC32();
						crcTest.update(bFileName);
						final long fCRC = crcTest.getValue();

						if (nameCRC32 != fCRC)
							return ZipReturn.ZIPCENTRALDIRERROR;

						final int charLen = blockLength - 5;

						final var dst = new byte[charLen];
						bb.get(dst);
						fileName = new String(dst, StandardCharsets.UTF_8); //$NON-NLS-1$

						break;
					}
					default:
						bb.position(bb.position() + blockLength);
						break;
				}
			}

			return ZipReturn.ZIPGOOD;
		}
		catch (Exception e)
		{
			System.err.println(e.getMessage());
			return ZipReturn.ZIPCENTRALDIRERROR;
		}

	}

	@Override
	public final void close() throws IOException
	{
		if (this.esbc != null)
			this.esbc.close();
	}

	public final void localFileAddDirectory() throws IOException
	{
		esbc.put((byte) 3);
		esbc.put((byte) 0);
	}

	public final void localFileCheck()
	{
		if (getFileStatus() != ZipReturn.ZIPUNTESTED)
			return;

		try
		{
			InputStream sInput = null;
			esbc.position(dataLocation);

			switch (compressionMethod)
			{
				case 8:
					sInput = new InflaterInputStream(esbc.getInputStream(), new Inflater(true));
					break;
				case 0:
				default:
					sInput = esbc.getInputStream();
					break;
			}

			if (sInput == null)
			{
				fileStatus = ZipReturn.ZIPERRORGETTINGDATASTREAM;
				return;
			}

			final int Buffersize = 8 * 1024;
			final var buffer = new byte[Buffersize];
			BigInteger sizetogo = getUncompressedSize();
			Checksum tcrc32 = new java.util.zip.CRC32();

			// Pre load the first buffer0
			int sizeNext = sizetogo.compareTo(BigInteger.valueOf(Buffersize)) > 0 ? Buffersize : sizetogo.intValue();
			int sizebuffer = sInput.read(buffer, 0, sizeNext);
			sizetogo = sizetogo.subtract(BigInteger.valueOf(sizebuffer));

			while (sizebuffer > 0)
			{
				sizeNext = sizetogo.compareTo(BigInteger.valueOf(Buffersize)) > 0 ? Buffersize : sizetogo.intValue();

				if (sizeNext > 0)
				{
					sizebuffer = sInput.read(buffer, 0, sizeNext);

					if (sizebuffer > 0)
					{
						tcrc32.update(buffer, 0, sizebuffer);
						sizetogo = sizetogo.subtract(BigInteger.valueOf(sizebuffer));
					}
				}
			}

			if (sizetogo.longValue() > 0)
			{
				fileStatus = ZipReturn.ZIPDECODEERROR;
				return;
			}

			byte[] testcrc = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UnsignedTypes.fromUInt(tcrc32.getValue())).array();

			fileStatus = Arrays.equals(getCrc(), testcrc) ? ZipReturn.ZIPGOOD : ZipReturn.ZIPCRCDECODEERROR;
		}
		catch (Exception e)
		{
			fileStatus = ZipReturn.ZIPDECODEERROR;
		}
	}

	public final ZipReturn localFileCloseReadStream() throws IOException
	{
		InputStream dfStream = readStream;
		if (dfStream != null)
		{
			// dfStream.close();
		}
		// close();
		return ZipReturn.ZIPGOOD;
	}

	public final ZipReturn localFileCloseWriteStream(byte[] crc32) throws IOException
	{
		OutputStream dfStream = writeStream;
		if (dfStream != null)
		{
			dfStream.flush();
			// dfStream.close();
		}

		compressedSize = BigInteger.valueOf(esbc.position() - dataLocation);

		if (compressedSize.longValue() == 0x0L && getUncompressedSize().longValue() == 0x0L)
		{
			localFileAddDirectory();
			compressedSize = BigInteger.valueOf(esbc.position() - dataLocation);
		}

		crc = crc32;
		writeCompressedSize();
		// close();
		return ZipReturn.ZIPGOOD;
	}

	public final ZipReturn localFileHeaderRead()
	{
		try
		{
			trrntZip = true;

			esbc.position(getRelativeOffsetOfLocalHeader().longValueExact());
			final var thisSignature = esbc.getInt();
			if (thisSignature != ZipFile.LOCALFILEHEADERSIGNATURE)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			esbc.getUShort(); // version needed to extract
			final int generalPurposeBitFlagLocal = esbc.getUShort();
			if (generalPurposeBitFlagLocal != getGeneralPurposeBitFlag())
				trrntZip = false;

			int tshort = esbc.getUShort();
			if (tshort != compressionMethod)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			tshort = esbc.getUShort();
			if (tshort != lastModFileTime)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			tshort = esbc.getUShort();
			if (tshort != lastModFileDate)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			byte[] tCRC = readCRC(esbc);
			if (((getGeneralPurposeBitFlag() & 8) == 0) && !Arrays.equals(tCRC, getCrc()))
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			final long tCompressedSize = esbc.getUInt();
			if (isZip64() && tCompressedSize != 0xffffffffL && tCompressedSize != compressedSize.longValue()) // if Zip64 File then the compressedSize should be 0xffffffff
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
			if ((getGeneralPurposeBitFlag() & 8) == 8 && tCompressedSize != 0) // if bit 4 set then no compressedSize is set yet
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
			if (!isZip64() && (getGeneralPurposeBitFlag() & 8) != 8 && tCompressedSize != compressedSize.longValue()) // check the compressedSize
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			final long tUnCompressedSize = esbc.getUInt();
			if (isZip64() && tUnCompressedSize != 0xffffffffL && tUnCompressedSize != getUncompressedSize().longValue()) // if Zip64 File then the unCompressedSize should be 0xffffffff
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
			if ((getGeneralPurposeBitFlag() & 8) == 8 && tUnCompressedSize != 0) // if bit 4 set then no unCompressedSize is set yet
				return ZipReturn.ZIPLOCALFILEHEADERERROR;
			if (!isZip64() && (getGeneralPurposeBitFlag() & 8) != 8 && tUnCompressedSize != getUncompressedSize().longValue()) // check the unCompressedSize
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			final int fileNameLength = esbc.getUShort();
			final int extraFieldLength = esbc.getUShort();

			final var bFileName = new byte[fileNameLength];
			esbc.get(bFileName);
			String tFileName = (generalPurposeBitFlagLocal & (1 << 11)) == 0 ? new String(bFileName, StandardCharsets.US_ASCII) : new String(bFileName, StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

			final var extraField = new byte[extraFieldLength];
			esbc.get(extraField);

			zip64 = false;
			ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
			while (extraFieldLength > bb.position())
			{
				int type = UnsignedTypes.toUShort(bb.getShort());
				int blockLength = UnsignedTypes.toUShort(bb.getShort());
				switch (type)
				{
					case 0x0001:
					{
						zip64 = true;
						if (tUnCompressedSize == 0xffffffffL)
						{
							final BigInteger tLong = UnsignedTypes.toULong(bb.getLong());
							if (tLong.compareTo(getUncompressedSize()) != 0)
								return ZipReturn.ZIPLOCALFILEHEADERERROR;
						}
						if (tCompressedSize == 0xffffffffL)
						{
							final BigInteger tLong = UnsignedTypes.toULong(bb.getLong());
							if (tLong.compareTo(compressedSize) != 0)
								return ZipReturn.ZIPLOCALFILEHEADERERROR;
						}
						break;
					}
					case 0x7075:
					{
						@SuppressWarnings("unused")
						final byte version = bb.get();
						final long nameCRC32 = UnsignedTypes.toUInt(bb.getInt());

						final var crcTest = new java.util.zip.CRC32();
						crcTest.update(bFileName);
						final long fCRC = crcTest.getValue();

						if (nameCRC32 != fCRC)
							return ZipReturn.ZIPLOCALFILEHEADERERROR;

						final int charLen = blockLength - 5;

						final var dst = new byte[charLen];
						bb.get(dst);
						fileName = new String(dst, StandardCharsets.UTF_8); //$NON-NLS-1$

						break;
					}
					default:
						bb.position(bb.position() + blockLength);
						break;
				}
			}

			if (!getFileName().equals(tFileName))
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			dataLocation = esbc.position();

			if ((getGeneralPurposeBitFlag() & 8) == 0)
				return ZipReturn.ZIPGOOD;

			esbc.position(esbc.position() + compressedSize.longValue());

			tCRC = readCRC(esbc);
			if (Arrays.equals(tCRC, new byte[] { 0x50, 0x4b, 0x07, 0x08 }))
				tCRC = readCRC(esbc);

			if (!Arrays.equals(tCRC, getCrc()))
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			long tint = esbc.getUInt();
			if (tint != compressedSize.longValue())
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			tint = esbc.getUInt();
			if (tint != getUncompressedSize().longValue())
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			return ZipReturn.ZIPGOOD;
		}
		catch (Exception e)
		{
			System.err.println(e.getMessage());
			return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}

	}

	public final ZipReturn localFileHeaderReadQuick()
	{
		try
		{
			trrntZip = true;

			esbc.position(getRelativeOffsetOfLocalHeader().longValue());
			final var thisSignature = esbc.getInt();
			if (thisSignature != ZipFile.LOCALFILEHEADERSIGNATURE)
				return ZipReturn.ZIPLOCALFILEHEADERERROR;

			esbc.getShort(); // version needed to extract
			generalPurposeBitFlag = esbc.getUShort();
			if ((getGeneralPurposeBitFlag() & 8) == 8)
				return ZipReturn.ZIPCANNOTFASTOPEN;

			compressionMethod = esbc.getUShort();
			lastModFileTime = esbc.getUShort();
			lastModFileDate = esbc.getUShort();
			crc = readCRC(esbc);
			compressedSize = BigInteger.valueOf(esbc.getUInt());
			uncompressedSize = BigInteger.valueOf(esbc.getUInt());

			final int fileNameLength = esbc.getUShort();
			final int extraFieldLength = esbc.getUShort();

			final var bFileName = new byte[fileNameLength];
			esbc.get(bFileName);
			@SuppressWarnings("unused")
			final String tFileName = (getGeneralPurposeBitFlag() & (1 << 11)) == 0 ? new String(bFileName, StandardCharsets.US_ASCII) : new String(bFileName, StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

			final var extraField = new byte[extraFieldLength];
			esbc.get(extraField);

			zip64 = false;
			final ByteBuffer bb = ByteBuffer.wrap(extraField).order(ByteOrder.LITTLE_ENDIAN);
			while (extraFieldLength > bb.position())
			{
				final int type = UnsignedTypes.toUShort(bb.getShort());
				final int blockLength = UnsignedTypes.toUShort(bb.getShort());
				switch (type)
				{
					case 0x0001:
					{
						zip64 = true;
						if (getUncompressedSize().compareTo(BigInteger.valueOf(0xffffffffL)) == 0)
							uncompressedSize = UnsignedTypes.toULong(bb.getLong());
						if (compressedSize.compareTo(BigInteger.valueOf(0xffffffffL)) == 0)
							compressedSize = UnsignedTypes.toULong(bb.getLong());
						break;
					}
					case 0x7075:
					{
						@SuppressWarnings("unused")
						final byte version = bb.get();
						final long nameCRC32 = UnsignedTypes.toUInt(bb.getInt());

						final var crcTest = new java.util.zip.CRC32();
						crcTest.update(bFileName);
						final long fCRC = crcTest.getValue();

						if (nameCRC32 != fCRC)
							return ZipReturn.ZIPLOCALFILEHEADERERROR;

						final int charLen = blockLength - 5;

						final var dst = new byte[charLen];
						bb.get(dst);
						fileName = new String(dst, StandardCharsets.UTF_8); //$NON-NLS-1$
						break;
					}
					default:
						bb.position(bb.position() + blockLength);
						break;
				}
			}

			dataLocation = esbc.position();
			return ZipReturn.ZIPGOOD;

		}
		catch (Exception e)
		{
			System.err.println(e.getMessage());
			return ZipReturn.ZIPLOCALFILEHEADERERROR;
		}

	}

	private final void localFileHeaderWrite() throws IOException
	{
		List<Byte> extraField = new ArrayList<>();
		zip64 = getUncompressedSize().compareTo(BigInteger.valueOf(0xffffffffL)) >= 0;

		final byte[] bFileName;

		if (!StandardCharsets.US_ASCII.newEncoder().canEncode(getFileName())) //$NON-NLS-1$
		{
			generalPurposeBitFlag = getGeneralPurposeBitFlag() | 1 << 11;
			bFileName = getFileName().getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
		}
		else
			bFileName = getFileName().getBytes(StandardCharsets.US_ASCII); //$NON-NLS-1$

		final int versionNeededToExtract = (isZip64() ? 45 : 20);

		relativeOffsetOfLocalHeader = BigInteger.valueOf(esbc.position());
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

		if (isZip64())
		{
			for (var i = 0; i < 20; i++)
				extraField.add((byte) 0);
		}

		final int fileNameLength = bFileName.length;
		esbc.putUShort(fileNameLength);

		final int extraFieldLength = extraField.size();
		esbc.putUShort(extraFieldLength);

		esbc.put(bFileName);

		extraLocation = esbc.position();
		for (byte b : extraField)
			esbc.put(b);
	}

	public final ZipReturn localFileOpenReadStream(boolean raw, AtomicReference<InputStream> stream, AtomicReference<BigInteger> streamSize, AtomicInteger cMethod, AtomicReference<Inflater> inflater) throws IOException
	{
		streamSize.set(BigInteger.valueOf(0));
		cMethod.set(compressionMethod);

		readStream = null;
		esbc.position(dataLocation);

		switch (compressionMethod)
		{
			case 8:
			{
				if (raw)
				{
					readStream = new BoundedInputStream(esbc.getInputStream(), compressedSize.longValue());
					streamSize.set(compressedSize);
				}
				else
				{
					if (inflater.get() == null)
						inflater.set(new Inflater(true));
					else
						inflater.get().reset();
					readStream = new InflaterInputStream(esbc.getInputStream(), inflater.get());
					streamSize.set(getUncompressedSize());

				}
				break;
			}
			default:
			case 0:
			{
				readStream = new BoundedInputStream(esbc.getInputStream(), compressedSize.longValue());
				streamSize.set(compressedSize); // same as UncompressedSize
				break;
			}
		}
		stream.set(readStream);
		return stream.get() == null ? ZipReturn.ZIPERRORGETTINGDATASTREAM : ZipReturn.ZIPGOOD;
	}

	public final ZipReturn localFileOpenWriteStream(boolean raw, boolean tZip, BigInteger uSize, int cMethod, AtomicReference<OutputStream> stream, AtomicReference<Deflater> deflater) throws IOException
	{
		uncompressedSize = uSize;
		compressionMethod = cMethod;

		localFileHeaderWrite();
		dataLocation = esbc.position();

		if (raw)
		{
			writeStream = esbc.getOutputStream();
			trrntZip = tZip;
		}
		else
		{
			if (cMethod == 0)
			{
				writeStream = esbc.getOutputStream();
				trrntZip = false;
			}
			else
			{
				if (deflater.get() == null)
					deflater.set(new Deflater(9, true));
				else
					deflater.get().reset();
				writeStream = new DeflaterOutputStream(esbc.getOutputStream(), deflater.get(), false);
				trrntZip = true;
			}
		}

		stream.set(writeStream);
		return stream.get() == null ? ZipReturn.ZIPERRORGETTINGDATASTREAM : ZipReturn.ZIPGOOD;
	}

	public final BigInteger localFilePos()
	{
		return getRelativeOffsetOfLocalHeader();
	}

	public final void localFilePos(BigInteger value)
	{
		relativeOffsetOfLocalHeader = value;
	}

	private final byte[] readCRC(EnhancedSeekableByteChannel esbc) throws IOException
	{
		return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(esbc.getInt()).array();
	}

	private final void writeCompressedSize() throws IOException
	{
		final long posNow = esbc.position();

		esbc.position(crc32Location);

		final long tCompressedSize;
		final long tUncompressedSize;
		if (isZip64())
		{
			tCompressedSize = 0xffffffffL;
			tUncompressedSize = 0xffffffffL;
		}
		else
		{
			tCompressedSize = compressedSize.longValue();
			tUncompressedSize = getUncompressedSize().longValue();
		}

		esbc.put(getCrc());
		esbc.putUInt(tCompressedSize);
		esbc.putUInt(tUncompressedSize);

		// also need to write extradata
		if (isZip64())
		{
			esbc.position(extraLocation);
			esbc.putUShort(0x0001); // id
			esbc.putUShort(16); // data length
			esbc.putULong(getUncompressedSize());
			esbc.putULong(compressedSize);
		}

		esbc.position(posNow);

	}

	/**
	 * @return the crc
	 */
	public byte[] getCrc()
	{
		return crc;
	}

	/**
	 * @return the fileName
	 */
	public String getFileName()
	{
		return fileName;
	}

	/**
	 * @return the fileStatus
	 */
	public ZipReturn getFileStatus()
	{
		return fileStatus;
	}

	/**
	 * @return the generalPurposeBitFlag
	 */
	public int getGeneralPurposeBitFlag()
	{
		return generalPurposeBitFlag;
	}

	/**
	 * @return the relativeOffsetOfLocalHeader
	 */
	public BigInteger getRelativeOffsetOfLocalHeader()
	{
		return relativeOffsetOfLocalHeader;
	}

	/**
	 * @return the uncompressedSize
	 */
	public BigInteger getUncompressedSize()
	{
		return uncompressedSize;
	}

	/**
	 * @return the zip64
	 */
	public boolean isZip64()
	{
		return zip64;
	}

	/**
	 * @return the trrntZip
	 */
	public boolean isTrrntZip()
	{
		return trrntZip;
	}

}