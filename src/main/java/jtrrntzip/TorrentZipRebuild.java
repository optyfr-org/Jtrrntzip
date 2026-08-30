package jtrrntzip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.DeflaterOutputStream;

import org.apache.commons.io.FilenameUtils;

import jtrrntzip.supportedfiles.ICompress;
import jtrrntzip.supportedfiles.zipfile.ZipFile;

public final class TorrentZipRebuild
{
	private TorrentZipRebuild()
	{
		throw new IllegalStateException("Utility class");
	}

	private static final Logger LOGGER = Logger.getLogger(TorrentZipRebuild.class.getName());

	public static final Set<TrrntZipStatus> reZipFiles(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final byte[] buffer, final LogCallback logCallback)
	{
		if (originalZipFile == null)
			throw new IllegalArgumentException("original zip file is <null>");

		final var filename = Path.of(originalZipFile.zipFilename());
		final var tmpFilename = filename.getParent().resolve(FilenameUtils.getBaseName(filename.getFileName().toString()) + ".tmp"); //$NON-NLS-1$
		final var outfilename = filename.getParent().resolve(FilenameUtils.getBaseName(filename.getFileName().toString()) + ".zip"); //$NON-NLS-1$

		try
		{
			return reZipFiles(zippedFiles, originalZipFile, buffer, logCallback, filename, tmpFilename, outfilename);
		}
		catch (final Exception e)
		{
			LOGGER.log(Level.WARNING, e, () -> "rebuild of " + filename + " failed");
			closeQuietly(originalZipFile);
			return EnumSet.of(TrrntZipStatus.CORRUPTZIP);
		}
	}

	/**
	 * @param zippedFiles
	 * @param originalZipFile
	 * @param buffer
	 * @param logCallback
	 * @param filename
	 * @param tmpFilename
	 * @param outfilename
	 * @return
	 * @throws IOException
	 */
	private static Set<TrrntZipStatus> reZipFiles(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final byte[] buffer, final LogCallback logCallback, final Path filename, final Path tmpFilename, final Path outfilename) throws IOException
	{
		Files.deleteIfExists(tmpFilename);
		final ICompress zipFileOut = new ZipFile();
		var corrupt = false;
		try
		{
			zipFileOut.zipFileCreate(tmpFilename.toFile());

			// by now the zippedFiles have been sorted so just loop over them
			for (var i = 0; i < zippedFiles.size(); i++)
			{
				logCallback.statusCallBack((int) ((double) (i + 1) / (zippedFiles.size()) * 100));

				final var t = zippedFiles.get(i);

				if (logCallback.isVerboseLogging())
					logCallback.statusLogCallBack(String.format("%15s %s %s", t.getSize(), t.toString(), t.getName())); //$NON-NLS-1$

				final AtomicReference<InputStream> readStream = new AtomicReference<>();
				final AtomicReference<BigInteger> streamSize = new AtomicReference<>();
				final var compMethod = new AtomicInteger();

				ZipReturn zrInput = ZipReturn.ZIPUNTESTED;
				if (originalZipFile instanceof ZipFile ozf)
				{
					zrInput = ozf.zipFileOpenReadStream(t.getIndex(), false, readStream, streamSize, compMethod);
				}

				final AtomicReference<OutputStream> writeStream = new AtomicReference<>();
				final ZipReturn zrOutput = zipFileOut.zipFileOpenWriteStream(false, true, t.getName(), streamSize.get(), (short) 8, writeStream);

				if (zrInput != ZipReturn.ZIPGOOD || zrOutput != ZipReturn.ZIPGOOD)
				{
					corrupt = true;
					break;
				}

				final var crcCs = new CheckedInputStream(readStream.get(), new CRC32());
				final var bcrcCs = new BufferedInputStream(crcCs, buffer.length);
				final var bWriteStream = new BufferedOutputStream(writeStream.get(), buffer.length);

				if (!copyFully(bcrcCs, bWriteStream, streamSize.get().longValue(), buffer))
				{
					corrupt = true;
					break;
				}

				bWriteStream.flush();
				if (writeStream.get() instanceof DeflaterOutputStream ws)
					ws.finish();

				originalZipFile.zipFileCloseReadStream();

				final long crc = crcCs.getChecksum().getValue();

				if ((int) crc != t.getCrc())
				{
					corrupt = true;
					break;
				}

				zipFileOut.zipFileCloseWriteStream(t.getLECRC());
			}

			if (!corrupt)
			{
				zipFileOut.zipFileClose();
				originalZipFile.zipFileClose();
				originalZipFile.close();
				if (!filename.equals(outfilename))
					Files.delete(filename);
				Files.copy(tmpFilename, outfilename, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
				Files.delete(tmpFilename);
				return EnumSet.of(TrrntZipStatus.VALIDTRRNTZIP);
			}

			return EnumSet.of(TrrntZipStatus.CORRUPTZIP);
		}
		finally
		{
			if (zipFileOut.zipOpen() != ZipOpenType.CLOSED)
			{
				try
				{
					zipFileOut.zipFileCloseFailed();
				}
				catch (final IOException e)
				{
					LOGGER.log(Level.FINE, e, () -> "failed close of " + tmpFilename);
				}
			}
			closeQuietly(originalZipFile);
			try
			{
				Files.deleteIfExists(tmpFilename);
			}
			catch (final IOException e)
			{
				LOGGER.log(Level.FINE, e, () -> "failed to delete " + tmpFilename);
			}
		}
	}

	static boolean copyFully(final InputStream in, final OutputStream out, final long size, final byte[] buffer) throws IOException
	{
		var total = 0L;
		while (total < size)
		{
			final var sizenow = (int) Math.min(buffer.length, size - total);
			final var n = in.read(buffer, 0, sizenow);
			if (n <= 0)
				return false;
			out.write(buffer, 0, n);
			total += n;
		}
		return true;
	}

	private static void closeQuietly(final ICompress zipFile)
	{
		try
		{
			zipFile.zipFileClose();
			zipFile.close();
		}
		catch (final IOException e)
		{
			LOGGER.log(Level.FINE, e, () -> "failed to close " + zipFile.zipFilename());
		}
	}

}
