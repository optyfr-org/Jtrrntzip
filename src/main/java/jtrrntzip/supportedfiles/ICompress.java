package jtrrntzip.supportedfiles;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jtrrntzip.ZipOpenType;
import jtrrntzip.ZipReturn;
import jtrrntzip.ZipStatus;
import jtrrntzip.supportedfiles.zipfile.ZipFile;

import java.util.EnumSet;

/**
 * The abstraction used by the torrentzip engine for every supported archive
 * format.
 *
 * <p>An instance represents one archive open for reading with
 * {@link #zipFileOpen(File, long, boolean)} or for writing with
 * {@link #zipFileCreate(File)}, exposes the metadata of its entries and
 * streams entry data in and out. Only the zip format is currently supported,
 * through {@link ZipFile}; the interface is sealed so implementors cannot
 * silently drift away from the contract.</p>
 */
public sealed interface ICompress extends Closeable permits ZipFile {

    /**
     * The opened read stream of a single archive entry.
     *
     * @param status
     *            the open result, {@link ZipReturn#ZIPGOOD} on success
     * @param stream
     *            the entry data stream, {@code null} when the open failed
     * @param size
     *            the number of bytes the stream delivers: the uncompressed
     *            size for decoding streams and the stored size for raw
     *            streams
     * @param compressionMethod
     *            the compression method of the entry
     */
    record OpenedReadStream(ZipReturn status, InputStream stream, long size, int compressionMethod) {
        /**
         * Creates a failed read stream open carrying no stream.
         *
         * @param status
         *            the failure reason, never {@link ZipReturn#ZIPGOOD}
         * @return the failed result
         */
        public static OpenedReadStream failed(final ZipReturn status) {
            return new OpenedReadStream(status, null, 0, 0);
        }
    }

    /**
     * The opened write stream of a single new archive entry.
     *
     * @param status
     *            the open result, {@link ZipReturn#ZIPGOOD} on success
     * @param stream
     *            the entry data stream, {@code null} when the open failed
     */
    record OpenedWriteStream(ZipReturn status, OutputStream stream) {
        /**
         * Creates a failed write stream open carrying no stream.
         *
         * @param status
         *            the failure reason, never {@link ZipReturn#ZIPGOOD}
         * @return the failed result
         */
        public static OpenedWriteStream failed(final ZipReturn status) {
            return new OpenedWriteStream(status, null);
        }
    }

    /**
     * Returns the number of entries of the opened archive.
     *
     * @return the entry count
     */
    int localFilesCount();

    /**
     * Returns the entry name of the entry at the given index.
     *
     * @param i
     *            the index of the entry
     * @return the decoded entry name
     */
    String filename(int i);

    /**
     * Returns the uncompressed size of the entry at the given index.
     *
     * @param i
     *            the index of the entry
     * @return the uncompressed size in bytes
     */
    long uncompressedSize(int i);

    /**
     * Returns the CRC-32 of the entry at the given index as the four little
     * endian bytes used by the on-disk structures.
     *
     * @param i
     *            the index of the entry
     * @return the CRC-32 as four little endian bytes
     */
    byte[] crc32(int i);

    /**
     * Returns the stored read status of the entry at the given index.
     *
     * @param i
     *            the index of the entry
     * @return the per entry status
     */
    ZipReturn fileStatus(int i);

    /**
     * Returns the current open state of the archive.
     *
     * @return the open state
     */
    ZipOpenType zipOpen();

    /**
     * Opens the given archive for reading.
     *
     * <p>The timestamp check protects against archives that change between
     * selection and processing: the file modification time must still equal
     * the given value when the file is opened. With {@code readHeaders} the
     * archive is fully parsed, otherwise only the file itself is held
     * open.</p>
     *
     * @param newFilename
     *            the archive file to open
     * @param timestamp
     *            the expected file modification time in milliseconds
     * @param readHeaders
     *            {@code true} to parse the archive structures right away
     * @return {@link ZipReturn#ZIPGOOD} when the archive was opened
     * @throws IOException
     *             when closing a previously failed open fails
     */
    ZipReturn zipFileOpen(File newFilename, long timestamp, boolean readHeaders) throws IOException;

    /**
     * Closes the archive.
     *
     * <p>When the archive is open for writing, this finalizes the written
     * archive: the central directory, the torrentzip file comment and, when
     * needed, the zip64 structures are written and the file is truncated to
     * its final size. When it is open for reading only, this releases the
     * file.</p>
     *
     * @throws IOException
     *             when finalizing or closing fails
     */
    void zipFileClose() throws IOException;

    /**
     * Opens the entry given by index for reading.
     *
     * @param index
     *            the index of the entry to read
     * @param raw
     *            {@code true} to return the compressed bytes as stored,
     *            {@code false} to return a decoding stream
     * @return the opened stream, failed when the entry cannot be read
     * @throws IOException
     *             when reading the entry header fails
     */
    OpenedReadStream zipFileOpenReadStream(int index, boolean raw) throws IOException;

    /**
     * Opens a new entry for writing into the given path inside the archive.
     *
     * @param raw
     *            {@code true} to write the data uncompressed as stored
     * @param trrntzip
     *            {@code true} when the entry is written in torrentzip mode
     * @param filename
     *            the entry path inside the archive
     * @param uncompressedSize
     *            the expected total number of bytes to be written
     * @param compressionMethod
     *            the compression method of the entry, for example 8 for
     *            deflate
     * @return the opened stream, failed when entries cannot be written
     * @throws IOException
     *             when writing the entry header fails
     */
    OpenedWriteStream zipFileOpenWriteStream(boolean raw, boolean trrntzip, String filename, long uncompressedSize, short compressionMethod) throws IOException;

    /**
     * Closes the read stream of the entry opened last.
     *
     * @return {@link ZipReturn#ZIPGOOD} when the stream was closed
     * @throws IOException
     *             when closing fails
     */
    ZipReturn zipFileCloseReadStream() throws IOException;

    /**
     * Returns the extra states of the opened archive.
     *
     * @return the extra states, empty for a plain opened archive
     */
    EnumSet<ZipStatus> zipStatus();

    /**
     * Returns the absolute path of the opened archive, or an empty string
     * when the archive is closed.
     *
     * @return the absolute path or an empty string
     */
    String zipFilename();

    /**
     * Returns the file modification time of the opened archive in
     * milliseconds, or 0 when the archive is closed.
     *
     * @return the modification time or 0
     */
    long timeStamp();

    /**
     * Creates and opens the given archive for writing.
     *
     * @param newFilename
     *            the archive file to create
     * @return {@link ZipReturn#ZIPGOOD} when the archive was created
     * @throws IOException
     *             when creating the file fails
     */
    ZipReturn zipFileCreate(File newFilename) throws IOException;

    /**
     * Finishes the entry written last and records its CRC-32.
     *
     * @param crc32
     *            the CRC-32 of the written data as four little endian bytes
     * @return {@link ZipReturn#ZIPGOOD} when the entry was finished
     * @throws IOException
     *             when finalizing the entry fails
     */
    ZipReturn zipFileCloseWriteStream(byte[] crc32) throws IOException;

    /**
     * Abandons the archive after a failed operation.
     *
     * <p>When the archive is open for writing, its file is deleted so no
     * half-written archive can remain; an archive open for reading is simply
     * closed.</p>
     *
     * @throws IOException
     *             when closing fails
     */
    void zipFileCloseFailed() throws IOException;
}
