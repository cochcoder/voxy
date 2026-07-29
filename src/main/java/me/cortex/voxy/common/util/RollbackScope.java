package me.cortex.voxy.common.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Collects cleanup actions while an object graph is being constructed.
 *
 * <p>Unless {@link #commit()} is called, closing the scope executes every
 * active action in reverse registration order. Cleanup failures are aggregated
 * so one broken resource cannot prevent the remaining resources from being
 * released.
 */
public final class RollbackScope implements AutoCloseable {
    @FunctionalInterface
    public interface Cleanup {
        void run() throws Exception;
    }

    public interface Registration {
        void cancel();
    }

    private final Deque<Entry> cleanups = new ArrayDeque<>();
    private boolean committed;
    private boolean closed;

    public Registration defer(Cleanup cleanup) {
        this.ensureOpen();
        var entry = new Entry(Objects.requireNonNull(cleanup, "cleanup"));
        this.cleanups.push(entry);
        return () -> entry.active = false;
    }

    public void commit() {
        this.ensureOpen();
        this.committed = true;
        this.cleanups.clear();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.committed) {
            return;
        }

        Throwable failure = null;
        while (!this.cleanups.isEmpty()) {
            var entry = this.cleanups.pop();
            if (!entry.active) {
                continue;
            }
            try {
                entry.cleanup.run();
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        rethrow(failure);
    }

    private void ensureOpen() {
        if (this.closed || this.committed) {
            throw new IllegalStateException("rollback scope is no longer open");
        }
    }

    private static void rethrow(Throwable throwable) {
        if (throwable == null) {
            return;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("resource rollback failed", throwable);
    }

    private static final class Entry {
        private final Cleanup cleanup;
        private boolean active = true;

        private Entry(Cleanup cleanup) {
            this.cleanup = cleanup;
        }
    }
}
