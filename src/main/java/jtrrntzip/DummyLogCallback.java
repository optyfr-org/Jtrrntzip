package jtrrntzip;

/**
 * A {@link LogCallback} that discards every notification, for embedding the
 * engine and for tests when no output is wanted.
 */
public final class DummyLogCallback implements LogCallback {

    /**
     * Creates a callback that ignores all notifications.
     */
    public DummyLogCallback() {
        // stateless, nothing to initialize
    }

    /**
     * Does nothing.
     *
     * @param percent
     *            ignored
     */
    @Override
    public void statusCallBack(final int percent) {
        // do nothing
    }

    /**
     * Never requests verbose output.
     *
     * @return always {@code false}
     */
    @Override
    public boolean isVerboseLogging() {
        return false;
    }

    /**
     * Does nothing.
     *
     * @param log
     *            ignored
     */
    @Override
    public void statusLogCallBack(final String log) {
        // do nothing
    }

}
