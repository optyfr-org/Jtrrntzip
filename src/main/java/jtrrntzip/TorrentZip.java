package jtrrntzip;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import jtrrntzip.supportedfiles.zipfile.ZipFile;

/**
 * The high level engine that checks a single zip archive against the
 * torrentzip format and converts it when needed.
 *
 * <p>Processing an archive takes three steps:</p>
 * <ol>
 * <li>open the archive and fail out when it is structurally corrupt,</li>
 * <li>read its entries and check them against the torrentzip rules with
 * {@link TorrentZipCheck}, which also repairs fixable violations in
 * memory,</li>
 * <li>rebuild the archive with {@link TorrentZipRebuild} when problems were
 * found.</li>
 * </ol>
 * <p>Archives that are already valid torrentzips are skipped unless
 * {@link TorrentZipOptions#isForceRezip()} is set. In check only mode, see
 * {@link TorrentZipOptions#isCheckOnly()}, problems are reported and the
 * rebuild is suppressed.</p>
 */
public final class TorrentZip {
    private static final String MSG_ZIP_FILE_CORRUPT = "TorrentZip.ZipFileCorrupt"; //$NON-NLS-1$

    private final LogCallback statusLogCallBack;
    private final TorrentZipOptions options;

    /**
     * The scratch buffer reused by every entry copy operation of a rebuild,
     * sized 64 KiB.
     */
    private final byte[] buffer;

    /**
     * Creates the engine.
     *
     * @param statusLogCallBack
     *            receives the log and progress output of the processing
     * @param options
     *            the processing options
     */
    public TorrentZip(final LogCallback statusLogCallBack, final TorrentZipOptions options) {
        this.statusLogCallBack = statusLogCallBack;
        this.options = options;
        buffer = new byte[64 * 1024];
    }

    /**
     * Checks the archive and, when allowed, converts it to the torrentzip
     * format.
     *
     * @param f
     *            the zip archive to process
     * @return the statuses found for this archive: contains
     *         {@link TrrntZipStatus#VALIDTRRNTZIP} when the archive satisfies
     *         the format afterwards or already did, and
     *         {@link TrrntZipStatus#CORRUPTZIP} when it could not be
     *         processed; other values name the individual rule violations
     * @throws IOException
     *             when reading or writing the archive fails
     */
    public final Set<TrrntZipStatus> process(final File f) throws IOException {
        if (statusLogCallBack.isVerboseLogging())
            statusLogCallBack.statusLogCallBack(""); //$NON-NLS-1$

        statusLogCallBack.statusLogCallBack(f.getName() + " - "); //$NON-NLS-1$

        // First open the zip file, and fail out if it is corrupt.

        try (final var zipFile = new ZipFile()) {
            // this will return ValidTrrntZip or CorruptZip.
            final var tzs = openZip(f, zipFile);

            if (tzs.contains(TrrntZipStatus.CORRUPTZIP)) {
                statusLogCallBack.statusLogCallBack(Messages.getString(MSG_ZIP_FILE_CORRUPT));
                return tzs;
            }

            // the zip file may have found a valid trrntzip header, but we now check that all the file info
            // is actually valid, and may invalidate it being a valid trrntzip if any problem is found.

            final List<ZippedFile> zippedFiles = readZipContent(zipFile);
            tzs.addAll(TorrentZipCheck.checkZipFiles(zippedFiles, statusLogCallBack));

            // the archive only stays a valid torrentzip when no problem was found

            if (tzs.contains(TrrntZipStatus.VALIDTRRNTZIP) && !options.isForceRezip()) {
                statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.SkippingFile")); //$NON-NLS-1$
                zipFile.zipFileClose();
                return tzs;
            }
            if (options.isCheckOnly()) {
                statusLogCallBack.statusLogCallBack(tzs.toString());
                zipFile.zipFileClose();
                return tzs;
            }
            // differing duplicate entries mark the zip corrupt, a rebuild cannot fix them
            if (tzs.contains(TrrntZipStatus.CORRUPTZIP)) {
                statusLogCallBack.statusLogCallBack(Messages.getString(MSG_ZIP_FILE_CORRUPT));
                zipFile.zipFileClose();
                return tzs;
            }
            statusLogCallBack.statusLogCallBack(Messages.getString("TorrentZip.TorrentZipping")); //$NON-NLS-1$
            final Set<TrrntZipStatus> rebuilt = TorrentZipRebuild.reZipFiles(zippedFiles, zipFile, buffer, statusLogCallBack);
            if (rebuilt.contains(TrrntZipStatus.CORRUPTZIP))
                statusLogCallBack.statusLogCallBack(Messages.getString(MSG_ZIP_FILE_CORRUPT));
            return rebuilt;
        }
    }

    /**
     * Opens the archive and detects its initial torrentzip state.
     *
     * @param f
     *            the zip archive to open
     * @param zipFile
     *            the archive instance opening the file
     * @return {@link TrrntZipStatus#VALIDTRRNTZIP} when the open archive
     *         claims to be a valid torrentzip, or
     *         {@link TrrntZipStatus#CORRUPTZIP} when opening failed
     * @throws IOException
     *             when closing a failed archive fails
     */
    private final EnumSet<TrrntZipStatus> openZip(final File f, final ZipFile zipFile) throws IOException {
        final ZipReturn zr = zipFile.zipFileOpen(f, f.lastModified(), true);
        if (zr != ZipReturn.ZIPGOOD) {
            return EnumSet.of(TrrntZipStatus.CORRUPTZIP);
        }

        final EnumSet<TrrntZipStatus> tzStatus = EnumSet.noneOf(TrrntZipStatus.class);

        // first check if the file is a trrntzip file
        if (zipFile.zipStatus().contains(ZipStatus.TRRNTZIP))
            tzStatus.add(TrrntZipStatus.VALIDTRRNTZIP);

        return tzStatus;
    }

    /**
     * Reads the metadata of every entry of the opened archive.
     *
     * @param zipFile
     *            the opened archive to read
     * @return the entries of the archive in archive order
     */
    private final List<ZippedFile> readZipContent(final ZipFile zipFile) {
        final List<ZippedFile> zippedFiles = new ArrayList<>();
        for (var i = 0; i < zipFile.localFilesCount(); i++) {
            zippedFiles.add(new ZippedFile(i, zipFile.filename(i), zipFile.uncompressedSize(i), ZippedFile.crcFromLe(zipFile.crc32(i))));
        }
        return zippedFiles;
    }

}
