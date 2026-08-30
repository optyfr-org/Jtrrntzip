package jtrrntzip;

/**
 * The open state of an {@link jtrrntzip.supportedfiles.ICompress} archive.
 */
public enum ZipOpenType {
    /**
     * The archive is not open, no file is held.
     */
    CLOSED,
    /**
     * The archive is open for reading.
     */
    OPENREAD,
    /**
     * The archive is open for writing and is being created from scratch.
     */
    OPENWRITE
}
