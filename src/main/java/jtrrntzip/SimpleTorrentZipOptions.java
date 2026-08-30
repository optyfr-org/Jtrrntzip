package jtrrntzip;

public record SimpleTorrentZipOptions(boolean forceRezip, boolean checkOnly) implements TorrentZipOptions {
    @Override
    public boolean isForceRezip() {
        return forceRezip;
    }

    @Override
    public boolean isCheckOnly() {
        return checkOnly;
    }
}
