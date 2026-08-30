package jtrrntzip;

public class DummyLogCallback implements LogCallback {

    @Override
    public void statusCallBack(final int percent) {
        // do nothing
    }

    @Override
    public boolean isVerboseLogging() {
        return false;
    }

    @Override
    public void statusLogCallBack(final String log) {
        // do nothing
    }

}
