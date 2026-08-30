package jtrrntzip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.DeflaterOutputStream;

import org.apache.commons.io.FilenameUtils;

import jtrrntzip.supportedfiles.ICompress;
import jtrrntzip.supportedfiles.zipfile.ZipFile;

/**
 * Rebuilds a zip archive into the torrentzip format by copying its entries
 * into a newly created archive.
 *
 * <p>The entries are streamed one by one from the source archive into a
 * sibling file with the extension {@code .tmp}, deflated at the maximum
 * level with the fixed torrentzip timestamp. Only when every entry has been
 * copied and verified is the temporary archive closed and moved onto the
 * original name. Any failure closes the source, discards the temporary file
 * and reports the archive as corrupt, so the original file is never left in a
 * half-written state.</p>
 */
public final class TorrentZipRebuild {
    private TorrentZipRebuild() {
        throw new IllegalStateException("Utility class");
    }

    private static final Logger LOGGER = Logger.getLogger(TorrentZipRebuild.class.getName());

    /**
     * Rebuilds the archive of the given source into the torrentzip format.
     *
     * <p>The source archive is closed before returning, both on success and
     * on failure.</p>
     *
     * @param zippedFiles
     *            the archive entries to copy, already in their final
     *            torrentzip order
     * @param originalZipFile
     *            the archive to read the entry data from
     * @param buffer
     *            the scratch buffer used for the entry copy operations
     * @param logCallback
     *            receives the progress and optional log output
     * @return {@link TrrntZipStatus#VALIDTRRNTZIP} when the rebuild succeeded,
     *         otherwise {@link TrrntZipStatus#CORRUPTZIP}
     */
    public static final Set<TrrntZipStatus> reZipFiles(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final byte[] buffer, final LogCallback logCallback) {
        if (originalZipFile == null)
            throw new IllegalArgumentException("original zip file is <null>");

        final var filename = Path.of(originalZipFile.zipFilename());
        final var tmpFilename = filename.getParent().resolve(FilenameUtils.getBaseName(filename.getFileName().toString()) + ".tmp"); //$NON-NLS-1$
        final var outfilename = filename.getParent().resolve(FilenameUtils.getBaseName(filename.getFileName().toString()) + ".zip"); //$NON-NLS-1$

        try {
            return reZipFiles(zippedFiles, originalZipFile, buffer, logCallback, filename, tmpFilename, outfilename);
        } catch (final Exception e) {
            LOGGER.log(Level.WARNING, e, () -> "rebuild of " + filename + " failed");
            closeQuietly(originalZipFile);
            return EnumSet.of(TrrntZipStatus.CORRUPTZIP);
        }
    }

    /**
     * Runs the rebuild against the resolved file names.
     *
     * @param zippedFiles
     *            the entries to copy, in their final order
     * @param originalZipFile
     *            the source archive to read from and close
     * @param buffer
     *            the scratch buffer for the copy operations
     * @param logCallback
     *            receives progress and optional log output
     * @param filename
     *            the original archive path
     * @param tmpFilename
     *            the temporary build path
     * @param outfilename
     *            the final output path, different from the original when its
     *            extension is not {@code .zip}
     * @return the rebuild result
     * @throws IOException
     *             when any step of the rebuild fails
     */
    private static Set<TrrntZipStatus> reZipFiles(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final byte[] buffer, final LogCallback logCallback,
            final Path filename, final Path tmpFilename, final Path outfilename) throws IOException {
        Files.deleteIfExists(tmpFilename);
        try (ICompress zipFileOut = new ZipFile()) {
            try {
                zipFileOut.zipFileCreate(tmpFilename.toFile());
                if (!copyAllEntries(zippedFiles, originalZipFile, zipFileOut, buffer, logCallback))
                    return EnumSet.of(TrrntZipStatus.CORRUPTZIP);

                finishRebuild(zipFileOut, originalZipFile, filename, tmpFilename, outfilename);
                return EnumSet.of(TrrntZipStatus.VALIDTRRNTZIP);
            } finally {
                abortOutputIfOpen(zipFileOut, tmpFilename);
                closeQuietly(originalZipFile);
                deleteQuietly(tmpFilename);
            }
        }
    }

    /**
     * Copies every entry from the source archive into the new archive and
     * reports progress per entry.
     *
     * @param zippedFiles
     *            the entries to copy
     * @param originalZipFile
     *            the source archive
     * @param zipFileOut
     *            the archive being written
     * @param buffer
     *            the scratch buffer for the copy operations
     * @param logCallback
     *            receives progress and optional log output
     * @return {@code true} when all entries were copied successfully
     * @throws IOException
     *             when reading or writing the archives fails
     */
    private static boolean copyAllEntries(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final ICompress zipFileOut, final byte[] buffer,
            final LogCallback logCallback) throws IOException {
        for (var i = 0; i < zippedFiles.size(); i++) {
            logCallback.statusCallBack((int) ((double) (i + 1) / zippedFiles.size() * 100));
            final var t = zippedFiles.get(i);
            if (logCallback.isVerboseLogging())
                logCallback.statusLogCallBack(String.format("%15s %s %s", t.size(), t.toString(), t.name())); //$NON-NLS-1$
            if (!copyEntry(t, originalZipFile, zipFileOut, buffer))
                return false;
        }
        return true;
    }

    /**
     * Copies a single entry from the source archive into the new archive.
     *
     * <p>The entry data is inflated, verified against the expected CRC-32 of
     * the central directory, and written deflated into the new archive.</p>
     *
     * @param t
     *            the entry to copy
     * @param originalZipFile
     *            the source archive
     * @param zipFileOut
     *            the archive being written
     * @param buffer
     *            the scratch buffer for the copy operation
     * @return {@code true} when the entry was copied successfully
     * @throws IOException
     *             when reading or writing the entry fails
     */
    private static boolean copyEntry(final ZippedFile t, final ICompress originalZipFile, final ICompress zipFileOut, final byte[] buffer) throws IOException {
        if (!(originalZipFile instanceof ZipFile ozf))
            return false;

        final var read = ozf.zipFileOpenReadStream(t.index(), false);
        if (read.status() != ZipReturn.ZIPGOOD)
            return false;

        final var write = zipFileOut.zipFileOpenWriteStream(false, true, t.name(), read.size(), (short) 8);
        if (write.status() != ZipReturn.ZIPGOOD)
            return false;

        final var crcCs = new CheckedInputStream(read.stream(), new CRC32());
        final var bcrcCs = new BufferedInputStream(crcCs, buffer.length);
        final var bWriteStream = new BufferedOutputStream(write.stream(), buffer.length);

        if (!copyFully(bcrcCs, bWriteStream, read.size(), buffer))
            return false;

        bWriteStream.flush();
        if (write.stream() instanceof DeflaterOutputStream ws)
            ws.finish();

        originalZipFile.zipFileCloseReadStream();
        if ((int) crcCs.getChecksum().getValue() != t.crc())
            return false;

        zipFileOut.zipFileCloseWriteStream(t.leCrc());
        return true;
    }

    /**
     * Finalizes a successful rebuild: closes both archives, removes the
     * original when the output name changes, and moves the temporary archive
     * onto the output name.
     *
     * @param zipFileOut
     *            the finished temporary archive
     * @param originalZipFile
     *            the source archive
     * @param filename
     *            the original archive path
     * @param tmpFilename
     *            the temporary build path
     * @param outfilename
     *            the final output path
     * @throws IOException
     *             when closing or moving the archives fails
     */
    private static void finishRebuild(final ICompress zipFileOut, final ICompress originalZipFile, final Path filename, final Path tmpFilename, final Path outfilename)
            throws IOException {
        zipFileOut.zipFileClose();
        originalZipFile.zipFileClose();
        originalZipFile.close();
        if (!filename.equals(outfilename))
            Files.delete(filename);
        Files.copy(tmpFilename, outfilename, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(tmpFilename);
    }

    /**
     * Aborts the output archive after a failed rebuild and leaves its
     * temporary file for deletion by the caller.
     *
     * @param zipFileOut
     *            the output archive, may be closed already
     * @param tmpFilename
     *            the temporary build path, used for logging only
     */
    private static void abortOutputIfOpen(final ICompress zipFileOut, final Path tmpFilename) {
        if (zipFileOut.zipOpen() == ZipOpenType.CLOSED)
            return;
        try {
            zipFileOut.zipFileCloseFailed();
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "failed close of " + tmpFilename);
        }
    }

    /**
     * Deletes the given path, ignoring any failure.
     *
     * @param tmpFilename
     *            the path to delete
     */
    private static void deleteQuietly(final Path tmpFilename) {
        try {
            Files.deleteIfExists(tmpFilename);
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "failed to delete " + tmpFilename);
        }
    }

    /**
     * Copies exactly {@code size} bytes from the input to the output stream.
     *
     * @param in
     *            the stream to read from
     * @param out
     *            the stream to write to
     * @param size
     *            the exact number of bytes to transfer
     * @param buffer
     *            the scratch buffer used for the transfer
     * @return {@code true} when exactly {@code size} bytes were copied,
     *         {@code false} when the input ended early
     * @throws IOException
     *             when reading or writing fails
     */
    static boolean copyFully(final InputStream in, final OutputStream out, final long size, final byte[] buffer) throws IOException {
        var total = 0L;
        while (total < size) {
            final var sizenow = (int) Math.min(buffer.length, size - total);
            final var n = in.read(buffer, 0, sizenow);
            if (n <= 0)
                return false;
            out.write(buffer, 0, n);
            total += n;
        }
        return true;
    }

    /**
     * Closes the given archive, ignoring any failure.
     *
     * @param zipFile
     *            the archive to close
     */
    private static void closeQuietly(final ICompress zipFile) {
        try {
            zipFile.zipFileClose();
            zipFile.close();
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "failed to close " + zipFile.zipFilename());
        }
    }

}
