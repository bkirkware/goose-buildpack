package org.tanzu.goose.cf;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Interface for executing Goose CLI commands from Java applications.
 * <p>
 * This interface provides multiple execution modes:
 * </p>
 * <ul>
 *   <li><b>Synchronous execution</b> - blocks until command completes</li>
 *   <li><b>Asynchronous execution</b> - returns immediately with a CompletableFuture</li>
 *   <li><b>Streaming execution</b> - returns a Stream of output lines as they arrive</li>
 * </ul>
 *
 * <h2>Single-Shot Execution</h2>
 * <p>For one-off prompts:</p>
 * <pre>{@code
 * GooseExecutor executor = new GooseExecutorImpl();
 * 
 * // Synchronous execution
 * String result = executor.execute("Analyze this code for bugs");
 * 
 * // Asynchronous execution
 * CompletableFuture<String> future = executor.executeAsync("Generate unit tests");
 * future.thenAccept(result -> System.out.println(result));
 * 
 * // Streaming execution (MUST use try-with-resources)
 * try (Stream<String> lines = executor.executeStreaming("Refactor this function")) {
 *     lines.forEach(System.out::println);
 * }
 * }</pre>
 *
 * <h2>Key Differences from Claude Code</h2>
 * <ul>
 *   <li>Goose is a native Rust binary (no Node.js required)</li>
 *   <li>Goose supports multiple LLM providers (Anthropic, OpenAI, Google, etc.)</li>
 *   <li>Goose CLI invocation: {@code goose session --text "prompt"}</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public interface GooseExecutor {

    /**
     * Execute a Goose command synchronously.
     * <p>
     * This method blocks until the Goose CLI command completes and returns
     * the full output as a single string.
     * </p>
     *
     * @param prompt the prompt to send to Goose
     * @return the complete output from Goose
     * @throws IllegalArgumentException if prompt is null or empty
     * @throws GooseExecutionException if command execution fails or times out
     */
    String execute(String prompt);

    /**
     * Execute a Goose command synchronously with options.
     * <p>
     * This method provides more control over command execution, including
     * custom timeout values, model selection, and additional CLI flags.
     * </p>
     *
     * @param prompt the prompt to send to Goose
     * @param options execution options (timeout, model, etc.)
     * @return the complete output from Goose
     * @throws IllegalArgumentException if prompt is null or empty
     * @throws GooseExecutionException if command execution fails or times out
     */
    String execute(String prompt, GooseOptions options);

    /**
     * Execute a Goose command asynchronously.
     * <p>
     * This method returns immediately with a CompletableFuture that will be
     * completed when the Goose command finishes. The future can be used
     * to attach callbacks or wait for completion at a later time.
     * </p>
     *
     * @param prompt the prompt to send to Goose
     * @return a CompletableFuture that completes with the command output
     * @throws IllegalArgumentException if prompt is null or empty
     */
    CompletableFuture<String> executeAsync(String prompt);

    /**
     * Execute a Goose command asynchronously with options.
     *
     * @param prompt the prompt to send to Goose
     * @param options execution options (timeout, model, etc.)
     * @return a CompletableFuture that completes with the command output
     * @throws IllegalArgumentException if prompt is null or empty
     */
    CompletableFuture<String> executeAsync(String prompt, GooseOptions options);

    /**
     * Execute a Goose command and return output as a stream of lines.
     * <p>
     * This method returns a Stream that emits each line of output as it becomes
     * available from the Goose CLI process. This enables true real-time streaming
     * where lines are processed as they are produced, not after the command completes.
     * </p>
     * <p>
     * <strong>CRITICAL - Resource Management:</strong> The returned Stream manages an
     * active subprocess and must be closed to prevent resource leaks. Always use
     * try-with-resources or explicitly call {@code close()} on the Stream:
     * </p>
     * <pre>{@code
     * // Recommended: try-with-resources
     * try (Stream<String> lines = executor.executeStreaming("analyze code")) {
     *     lines.forEach(System.out::println);
     * }
     * }</pre>
     *
     * @param prompt the prompt to send to Goose
     * @return a Stream of output lines that must be closed when done
     * @throws IllegalArgumentException if prompt is null or empty
     * @throws GooseExecutionException if command execution fails to start
     */
    Stream<String> executeStreaming(String prompt);

    /**
     * Execute a Goose command and return output as a stream of lines with options.
     *
     * @param prompt the prompt to send to Goose
     * @param options execution options (timeout, model, etc.)
     * @return a Stream of output lines that must be closed when done
     * @throws IllegalArgumentException if prompt is null or empty
     * @throws GooseExecutionException if command execution fails to start
     */
    Stream<String> executeStreaming(String prompt, GooseOptions options);

    /**
     * Check if Goose CLI is available and properly configured.
     * <p>
     * This method performs basic health checks:
     * </p>
     * <ul>
     *   <li>Verifies GOOSE_CLI_PATH environment variable is set</li>
     *   <li>Verifies the CLI executable exists</li>
     *   <li>Verifies at least one LLM provider API key is set</li>
     * </ul>
     *
     * @return true if Goose CLI is available, false otherwise
     */
    boolean isAvailable();

    /**
     * Get the version of the Goose CLI.
     *
     * @return the version string, or null if version cannot be determined
     */
    String getVersion();

    // ==================== Session Management ====================

    /**
     * Execute a Goose command within a named session.
     * <p>
     * Named sessions allow multi-turn conversations where Goose maintains
     * context across multiple invocations. Sessions are stored in Goose's
     * SQLite database at {@code ~/.local/share/goose/sessions/sessions.db}.
     * </p>
     * <p>
     * For new sessions, this starts a session with the given name.
     * For existing sessions, use {@link #executeInSession(String, String, boolean, GooseOptions)}
     * with {@code resume=true} to continue the conversation.
     * </p>
     *
     * @param sessionName the name for this session (e.g., "chat-session-abc123")
     * @param prompt the prompt to send to Goose
     * @param resume if true, resumes an existing session; if false, starts new
     * @return the complete output from Goose
     * @throws IllegalArgumentException if sessionName or prompt is null or empty
     * @throws GooseExecutionException if command execution fails or times out
     */
    String executeInSession(String sessionName, String prompt, boolean resume);

    /**
     * Execute a Goose command within a named session with options.
     *
     * @param sessionName the name for this session
     * @param prompt the prompt to send to Goose
     * @param resume if true, resumes an existing session; if false, starts new
     * @param options execution options (timeout, model, etc.)
     * @return the complete output from Goose
     * @throws IllegalArgumentException if sessionName or prompt is null or empty
     * @throws GooseExecutionException if command execution fails or times out
     */
    String executeInSession(String sessionName, String prompt, boolean resume, GooseOptions options);

    /**
     * Execute a Goose command within a named session and return output as a stream.
     * <p>
     * Combines session management with streaming output for real-time response handling.
     * </p>
     *
     * @param sessionName the name for this session
     * @param prompt the prompt to send to Goose
     * @param resume if true, resumes an existing session; if false, starts new
     * @return a Stream of output lines that must be closed when done
     * @throws IllegalArgumentException if sessionName or prompt is null or empty
     * @throws GooseExecutionException if command execution fails to start
     */
    Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume);

    /**
     * Execute a Goose command within a named session and return output as a stream with options.
     *
     * @param sessionName the name for this session
     * @param prompt the prompt to send to Goose
     * @param resume if true, resumes an existing session; if false, starts new
     * @param options execution options (timeout, model, etc.)
     * @return a Stream of output lines that must be closed when done
     * @throws IllegalArgumentException if sessionName or prompt is null or empty
     * @throws GooseExecutionException if command execution fails to start
     */
    Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume, GooseOptions options);
}

