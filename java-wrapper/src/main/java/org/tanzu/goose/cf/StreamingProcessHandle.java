package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle and timeout enforcement for streaming Goose CLI processes.
 * <p>
 * This class wraps a {@link Process} and provides:
 * </p>
 * <ul>
 *   <li>Automatic timeout enforcement using a scheduled task</li>
 *   <li>Graceful shutdown with fallback to forcible termination</li>
 *   <li>Thread-safe close semantics with idempotent behavior</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class StreamingProcessHandle implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StreamingProcessHandle.class);

    // Shared executor service for timeout management
    private static final ScheduledExecutorService timeoutExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "goose-timeout");
                t.setDaemon(true);
                return t;
            });

    private final Process process;
    private final ScheduledFuture<?> timeoutTask;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new streaming process handle.
     *
     * @param process the process to manage
     * @param timeout the maximum duration before the process is forcibly terminated
     */
    public StreamingProcessHandle(Process process, Duration timeout) {
        this.process = process;

        this.timeoutTask = timeoutExecutor.schedule(() -> {
            if (process.isAlive()) {
                logger.warn("Streaming process timed out after {}, forcibly destroying", timeout);
                process.destroyForcibly();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        logger.debug("Created streaming process handle with timeout: {}", timeout);
    }

    /**
     * Gets the underlying process.
     *
     * @return the managed process
     */
    public Process getProcess() {
        return process;
    }

    /**
     * Checks if the process is still alive.
     *
     * @return true if the process is running
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Gets the exit value if the process has terminated.
     *
     * @return the exit code
     * @throws IllegalThreadStateException if the process has not terminated
     */
    public int exitValue() {
        return process.exitValue();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.debug("Closing streaming process handle");

            timeoutTask.cancel(false);

            if (process.isAlive()) {
                logger.debug("Process still alive, destroying gracefully");
                process.destroy();

                try {
                    boolean terminated = process.waitFor(1, TimeUnit.SECONDS);
                    if (!terminated) {
                        logger.warn("Process did not terminate gracefully, forcing");
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for process termination", e);
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            } else {
                logger.debug("Process already terminated with exit code: {}", process.exitValue());
            }
        }
    }
}
