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

public final class TorrentZipRebuild {
    private TorrentZipRebuild() {
        throw new IllegalStateException("Utility class");
    }

    private static final Logger LOGGER = Logger.getLogger(TorrentZipRebuild.class.getName());

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

    private static boolean copyAllEntries(final List<ZippedFile> zippedFiles, final ICompress originalZipFile, final ICompress zipFileOut, final byte[] buffer,
            final LogCallback logCallback) throws IOException {
        for (var i = 0; i < zippedFiles.size(); i++) {
            logCallback.statusCallBack((int) ((double) (i + 1) / zippedFiles.size() * 100));
            final var t = zippedFiles.get(i);
            if (logCallback.isVerboseLogging())
                logCallback.statusLogCallBack(String.format("%15s %s %s", t.getSize(), t.toString(), t.getName())); //$NON-NLS-1$
            if (!copyEntry(t, originalZipFile, zipFileOut, buffer))
                return false;
        }
        return true;
    }

    private static boolean copyEntry(final ZippedFile t, final ICompress originalZipFile, final ICompress zipFileOut, final byte[] buffer) throws IOException {
        if (!(originalZipFile instanceof ZipFile ozf))
            return false;

        final var read = ozf.zipFileOpenReadStream(t.getIndex(), false);
        if (read.status() != ZipReturn.ZIPGOOD)
            return false;

        final var write = zipFileOut.zipFileOpenWriteStream(false, true, t.getName(), read.size(), (short) 8);
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
        if ((int) crcCs.getChecksum().getValue() != t.getCrc())
            return false;

        zipFileOut.zipFileCloseWriteStream(t.getLECRC());
        return true;
    }

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

    private static void abortOutputIfOpen(final ICompress zipFileOut, final Path tmpFilename) {
        if (zipFileOut.zipOpen() == ZipOpenType.CLOSED)
            return;
        try {
            zipFileOut.zipFileCloseFailed();
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "failed close of " + tmpFilename);
        }
    }

    private static void deleteQuietly(final Path tmpFilename) {
        try {
            Files.deleteIfExists(tmpFilename);
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "failed to delete " + tmpFilename);
        }
    }

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

    private static void closeQuietly(final ICompress zipFile) {
        try {
            zipFile.zipFileClose();
            zipFile.close();
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "failed to close " + zipFile.zipFilename());
        }
    }

}
