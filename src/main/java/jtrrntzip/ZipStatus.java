package jtrrntzip;

public enum ZipStatus {
    NONE(0x0),
    TRRNTZIP(0x1),
    EXTRADATA(0x2);

    private int status;

    private ZipStatus(final int status) {
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
