package jtrrntzip;

/**
 * Receives progress notifications while an archive is being processed.
 */
public interface StatusCallback {
    /**
     * Reports the processing progress of the archive currently being
     * handled.
     *
     * @param percent
     *            the progress percentage, between 0 and 100
     */
    void statusCallBack(int percent);
}
