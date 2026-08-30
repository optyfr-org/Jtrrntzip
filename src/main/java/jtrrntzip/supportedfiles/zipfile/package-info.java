/**
 * Low level reading and writing of zip archives.
 *
 * <p>{@link ZipFile} implements the
 * {@link jtrrntzip.supportedfiles.ICompress} contract for the zip format: it
 * locates and validates the end of central directory record, supports the
 * zip64 extensions, verifies the torrentzip file comment checksum and writes
 * archives in the deterministic layout required for byte identical output.
 * Every archive entry is modeled by a {@link LocalFile}, which owns the entry
 * metadata and streams the entry data in and out of the underlying
 * channel.</p>
 */
package jtrrntzip.supportedfiles.zipfile;
