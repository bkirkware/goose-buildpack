package org.tanzu.goose.cf;

/**
 * Exception thrown when Goose CLI execution fails.
 * <p>
 * This exception is used to wrap various error conditions:
 * </p>
 * <ul>
 *   <li>Process execution failures</li>
 *   <li>Timeout exceeded</li>
 *   <li>Non-zero exit codes</li>
 *   <li>I/O errors during execution</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class GooseExecutionException extends RuntimeException {

    private final int exitCode;
    private final String output;

    /**
     * Create a new exception with a message.
     *
     * @param message the error message
     */
    public GooseExecutionException(String message) {
        super(message);
        this.exitCode = -1;
        this.output = null;
    }

    /**
     * Create a new exception with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public GooseExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
        this.output = null;
    }

    /**
     * Create a new exception with exit code and output.
     *
     * @param message the error message
     * @param exitCode the process exit code
     * @param output the process output (stdout + stderr)
     */
    public GooseExecutionException(String message, int exitCode, String output) {
        super(message);
        this.exitCode = exitCode;
        this.output = output;
    }

    /**
     * Get the process exit code, or -1 if not applicable.
     *
     * @return the exit code
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Get the process output captured before the error, or null if not available.
     *
     * @return the process output
     */
    public String getOutput() {
        return output;
    }
}

