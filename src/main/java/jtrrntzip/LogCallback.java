package jtrrntzip;

/**
 * Receives human readable log lines in addition to the progress notifications
 * of {@link StatusCallback}.
 */
public interface LogCallback extends StatusCallback {
    /**
     * Indicates if detailed messages about individual rule violations should
     * be produced.
     *
     * @return {@code true} when verbose logging is requested
     */
    boolean isVerboseLogging();

    /**
     * Receives a single log line. The line carries no trailing line break,
     * the implementation decides how it is terminated.
     *
     * @param log
     *            the log message
     */
    void statusLogCallBack(String log);
}
