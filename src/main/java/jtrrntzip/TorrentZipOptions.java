package jtrrntzip;

public sealed interface TorrentZipOptions permits SimpleTorrentZipOptions {
    boolean isForceRezip();

    boolean isCheckOnly();
}
