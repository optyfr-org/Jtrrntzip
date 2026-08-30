package jtrrntzip;

/**
 * The torrentzip validation result for a single archive.
 *
 * <p>The values are reported as a set: {@link #VALIDTRRNTZIP} and
 * {@link #CORRUPTZIP} are exclusive overall outcomes, while the remaining
 * values name the concrete rule violations that were found and usually make a
 * rebuild necessary.</p>
 */
public enum TrrntZipStatus {
    /**
     * The archive fully satisfies the torrentzip format, no rebuild is
     * needed.
     */
    VALIDTRRNTZIP,
    /**
     * The archive is structurally broken, for example its headers are
     * inconsistent or it holds conflicting duplicate entries; a rebuild
     * cannot repair it.
     */
    CORRUPTZIP,
    /**
     * The archive is readable but does not follow the torrentzip format.
     * Reserved from the original implementation, this project derives the
     * same information from the remaining values of this enum.
     */
    NOTTRRNTZIPPED,
    /**
     * At least one entry name uses a backslash instead of a slash as the
     * directory separator.
     */
    BADDIRECTORYSEPARATOR,
    /**
     * The entries are not sorted in the order the torrentzip format requires.
     */
    UNSORTED,
    /**
     * Directory marker entries exist for directories that also contain
     * files; only empty directories may have a marker entry.
     */
    EXTRADIRECTORYENTRIES,
    /**
     * Duplicate entries were found. Identical duplicates are dropped during
     * the check, entries that differ in content mark the archive corrupt.
     */
    REPEATFILESFOUND;
}
