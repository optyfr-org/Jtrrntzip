package jtrrntzip;

/**
 * The options controlling how {@link TorrentZip} processes a single archive.
 */
public sealed interface TorrentZipOptions permits SimpleTorrentZipOptions {
    /**
     * Tells if archives that already satisfy the torrentzip format must be
     * rebuilt anyway.
     *
     * @return {@code true} when valid torrentzips are rebuilt as well
     */
    boolean isForceRezip();

    /**
     * Tells if archives must only be checked, never repaired.
     *
     * @return {@code true} when problems are only reported and no archive is
     *         rewritten
     */
    boolean isCheckOnly();
}
