package jtrrntzip;

/**
 * An immutable record implementation of {@link TorrentZipOptions} for direct
 * programmatic use.
 *
 * @param forceRezip
 *            rebuild archives even when they are already valid torrentzips
 * @param checkOnly
 *            report problems without rewriting any archive
 */
public record SimpleTorrentZipOptions(boolean forceRezip, boolean checkOnly) implements TorrentZipOptions {
    /**
     * Tells if valid torrentzips are rebuilt anyway.
     *
     * @return the {@code forceRezip} flag given at construction
     */
    @Override
    public boolean isForceRezip() {
        return forceRezip;
    }

    /**
     * Tells if archives are only checked, never repaired.
     *
     * @return the {@code checkOnly} flag given at construction
     */
    @Override
    public boolean isCheckOnly() {
        return checkOnly;
    }
}
