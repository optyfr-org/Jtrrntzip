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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
			Optional.ofNullable(originalZipFile).ifPresent(t -> {
				try
				{
					t.zipFileClose();
					t.close();
				}
				catch (final IOException e1)
				{
					System.err.println(e1.getMessage());
				}
			});
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
	 * @throws Exception
	 */
	private static Set<TrrntZipStatus> reZipFiles(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final byte[] buffer, final LogCallback logCallback, final Path filename, final Path tmpFilename, final Path outfilename) throws IOException, Exception
	{
		Files.deleteIfExists(tmpFilename);
		try (ICompress zipFileOut = new ZipFile())
		{
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
					ZipFile z = null;
					if (originalZipFile instanceof ZipFile ozf)
					{
						z = ozf;
						zrInput = z.zipFileOpenReadStream(t.getIndex(), false, readStream, streamSize, compMethod);
					}

					final AtomicReference<OutputStream> writeStream = new AtomicReference<>();
					final ZipReturn zrOutput = zipFileOut.zipFileOpenWriteStream(false, true, t.getName(), streamSize.get(), (short) 8, writeStream);

					if (zrInput != ZipReturn.ZIPGOOD || zrOutput != ZipReturn.ZIPGOOD)
					{
						// Error writing local File.
						zipFileOut.zipFileClose();
						zipFileOut.close();
						originalZipFile.zipFileClose();
						Files.delete(tmpFilename);
						return EnumSet.of(TrrntZipStatus.CORRUPTZIP);
					}

					final var crcCs = new CheckedInputStream(readStream.get(), new CRC32());
					final var bcrcCs = new BufferedInputStream(crcCs, buffer.length);
					final var bWriteStream = new BufferedOutputStream(writeStream.get(), buffer.length);

					BigInteger sizetogo = streamSize.get();
					while (sizetogo.compareTo(BigInteger.valueOf(0)) > 0)
					{
						final int sizenow = sizetogo.compareTo(BigInteger.valueOf(buffer.length)) > 0 ? buffer.length : sizetogo.intValue();
						bcrcCs.read(buffer, 0, sizenow);
						bWriteStream.write(buffer, 0, sizenow);
						sizetogo = sizetogo.subtract(BigInteger.valueOf(sizenow));
					}
					bWriteStream.flush();
					if (writeStream.get() instanceof DeflaterOutputStream ws)
						ws.finish();

					if (z != null)
						originalZipFile.zipFileCloseReadStream();

					final long crc = crcCs.getChecksum().getValue();

					if ((int) crc != t.getCrc())
						return EnumSet.of(TrrntZipStatus.CORRUPTZIP);

					zipFileOut.zipFileCloseWriteStream(t.getLECRC());
				}

				zipFileOut.zipFileClose();
			}
			catch (Exception e)
			{
				if (zipFileOut != null)
					zipFileOut.zipFileCloseFailed();
				throw e;
			}
		}
		originalZipFile.zipFileClose();
		originalZipFile.close();
		if (!filename.equals(outfilename))
			Files.delete(filename);
		Files.copy(tmpFilename, outfilename, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
		Files.delete(tmpFilename);
		return EnumSet.of(TrrntZipStatus.VALIDTRRNTZIP);
	}

}
