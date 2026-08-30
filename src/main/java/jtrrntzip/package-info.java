/**
 * The command line front end and the high level torrentzip engine.
 *
 * <p>{@link Program} parses the command line into {@link CliOptions} and walks
 * every file, directory and glob pattern the user selected. Each zip found is
 * handed to {@link TorrentZip}, which opens it through the low level zip
 * support in {@link jtrrntzip.supportedfiles.zipfile.ZipFile}, validates its
 * entries against the torrentzip rules with {@link TorrentZipCheck} and, when
 * needed and allowed, rebuilds the archive with {@link TorrentZipRebuild}.</p>
 *
 * <p>Progress and problems are reported through the {@link LogCallback}
 * interface, the outcome of processing a single archive is described by a set
 * of {@link TrrntZipStatus} values.</p>
 */
package jtrrntzip;
