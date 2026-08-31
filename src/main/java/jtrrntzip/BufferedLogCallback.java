package jtrrntzip;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LogCallback} that buffers all output in memory and flushes it
 * atomically to a delegate. This prevents interleaved console output when
 * multiple archives are processed concurrently on virtual threads.
 *
 * <p>Each concurrent task creates its own {@code BufferedLogCallback} backed
 * by the same shared lock. When the task finishes, it calls
 * {@link #flushTo(PrintStream)} which acquires the lock and writes every
 * buffered line in one block.</p>
 */
final class BufferedLogCallback implements LogCallback {
    private final LogCallback delegate;
    private final Object lock;
    private final List<String> lines = new ArrayList<>();

    /**
     * Creates a buffered callback.
     *
     * @param delegate
     *            the real callback whose {@code isVerboseLogging()} is
     *            consulted
     * @param lock
     *            the shared lock used by {@link #flushTo} to serialize
     *            output blocks
     */
    BufferedLogCallback(final LogCallback delegate, final Object lock) {
        this.delegate = delegate;
        this.lock = lock;
    }

    @Override
    public void statusLogCallBack(final String log) {
        lines.add(log);
    }

    @Override
    public void statusCallBack(final int percent) {
        if (!lines.isEmpty()) {
            final int last = lines.size() - 1;
            lines.set(last, lines.get(last) + String.format("%03d%% ", percent));
        }
    }

    @Override
    public boolean isVerboseLogging() {
        return delegate.isVerboseLogging();
    }

    /**
     * Flushes all buffered lines to the given stream under the shared
     * lock so that no other task's output is interleaved.
     *
     * @param out
     *            the target stream, typically {@code System.out}
     */
    void flushTo(final java.io.PrintStream out) {
        synchronized (lock) {
            for (final String line : lines) {
                out.format("%s%n", line); // NOSONAR
            }
        }
    }
}
