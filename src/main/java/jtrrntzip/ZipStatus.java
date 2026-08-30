package jtrrntzip;

/**
 * Extra states that can be observed in an opened zip archive.
 *
 * <p>The states of a single archive are reported as a set because several
 * observations can apply at once, for example an archive can be a valid
 * torrentzip and still carry trailing bytes after the end of central
 * directory record.</p>
 */
public enum ZipStatus {
    /**
     * The archive has no noteworthy extra state.
     */
    NONE(0x0),
    /**
     * The archive carries a {@code TORRENTZIPPED-XXXXXXXX} file comment whose
     * checksum matches the central directory, and it also passed the
     * remaining torrentzip validations: the local header marks, the entry
     * sort order and the directory marker rules.
     */
    TRRNTZIP(0x1),
    /**
     * Trailing bytes were found after the end of the end of central directory
     * record.
     */
    EXTRADATA(0x2);

    private final int status;

    private ZipStatus(final int status) {
        this.status = status;
    }

    /**
     * Returns the numeric flag value of this state as used by the original
     * implementation.
     *
     * @return the numeric flag value
     */
    public int getStatus() {
        return status;
    }
}
