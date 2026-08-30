/**
 * Jtrrntzip, a Java tool that converts zip archives to the torrentzip format.
 *
 * <p>Torrentzip is a deterministic zip layout: identical inputs always produce
 * byte-identical archives, which makes the result compatible with
 * bittorrent-based archiving tools such as RomVault. A full rebuild is only
 * performed when an archive does not already satisfy the torrentzip rules; the
 * normative format description ships in the {@code specs/} folder of the
 * project repository.</p>
 *
 * <p>The module is organized in three layers:</p>
 * <ul>
 * <li>{@code jtrrntzip} - the command line front end and the high level check
 * and rebuild engine operating on the torrentzip rules</li>
 * <li>{@code jtrrntzip.supportedfiles} - archive independent abstractions:
 * the compression interface, an enhanced seekable byte channel and unsigned
 * integer helpers</li>
 * <li>{@code jtrrntzip.supportedfiles.zipfile} - a low level zip reader and
 * writer with torrentzip and zip64 support</li>
 * </ul>
 */
module trrntzip {
    exports jtrrntzip.supportedfiles;
    exports jtrrntzip.supportedfiles.zipfile;
    exports jtrrntzip;

    requires org.apache.commons.io;
    requires java.logging;
}
