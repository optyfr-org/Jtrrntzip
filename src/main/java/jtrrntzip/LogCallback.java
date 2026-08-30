package jtrrntzip;

public interface LogCallback extends StatusCallback {
    boolean isVerboseLogging();

    void statusLogCallBack(String log);
}
