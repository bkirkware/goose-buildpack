package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Default implementation of {@link GooseExecutor}.
 * <p>
 * This implementation uses {@link ProcessBuilder} to invoke the Goose CLI
 * and follows best practices for process management in Cloud Foundry environments.
 * </p>
 *
 * <h2>Critical Implementation Details</h2>
 * <p>
 * This implementation follows patterns to avoid common ProcessBuilder pitfalls:
 * </p>
 * <ul>
 *   <li>Always closes stdin immediately to prevent CLI from waiting for input</li>
 *   <li>Redirects stderr to stdout to prevent buffer deadlock</li>
 *   <li>Passes environment variables to subprocess explicitly</li>
 *   <li>Uses timeouts to prevent indefinite hangs</li>
 *   <li>Properly cleans up resources</li>
 * </ul>
 *
 * <h2>Environment Variables</h2>
 * <p>Required environment variables:</p>
 * <ul>
 *   <li><code>GOOSE_CLI_PATH</code> - Path to the Goose CLI executable</li>
 *   <li>One of: <code>ANTHROPIC_API_KEY</code>, <code>OPENAI_API_KEY</code>, 
 *       <code>GOOGLE_API_KEY</code>, etc.</li>
 *   <li><code>HOME</code> - Home directory (for Goose configuration)</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class GooseExecutorImpl implements GooseExecutor {

    private static final Logger logger = LoggerFactory.getLogger(GooseExecutorImpl.class);

    // Shared executor service for timeout management
    private static final ScheduledExecutorService timeoutExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "goose-timeout");
                t.setDaemon(true);
                return t;
            });

    private final String goosePath;
    private final Map<String, String> baseEnvironment;

    /**
     * Constructs a new executor using environment variables.
     * <p>
     * Requires the following environment variables to be set:
     * </p>
     * <ul>
     *   <li>GOOSE_CLI_PATH</li>
     *   <li>At least one LLM provider API key</li>
     * </ul>
     *
     * @throws IllegalStateException if required environment variables are not set
     */
    public GooseExecutorImpl() {
        this.goosePath = getRequiredEnv("GOOSE_CLI_PATH");
        this.baseEnvironment = buildBaseEnvironment();
        
        // Log Goose configuration status at startup
        logGooseConfiguration();
    }
    
    /**
     * Log Goose configuration status for troubleshooting.
     * This helps diagnose issues with config.yaml loading.
     */
    private void logGooseConfiguration() {
        String home = System.getenv("HOME");
        logger.info("Goose configuration check:");
        logger.info("  HOME = {}", home);
        logger.info("  GOOSE_CLI_PATH = {}", goosePath);
        logger.info("  GOOSE_PROVIDER = {}", System.getenv("GOOSE_PROVIDER"));
        logger.info("  GOOSE_MODEL = {}", System.getenv("GOOSE_MODEL"));
        logger.info("  GOOSE_CONFIG_DIR = {}", System.getenv("GOOSE_CONFIG_DIR"));
        
        if (home != null) {
            Path configDir = Paths.get(home, ".config", "goose");
            Path configFile = configDir.resolve("config.yaml");
            Path profilesFile = configDir.resolve("profiles.yaml");
            
            logger.info("  Config directory: {}", configDir);
            logger.info("    Exists: {}", Files.exists(configDir));
            
            if (Files.exists(configDir)) {
                try {
                    Files.list(configDir).forEach(path -> 
                        logger.info("    File: {}", path.getFileName()));
                } catch (IOException e) {
                    logger.warn("    Error listing config directory: {}", e.getMessage());
                }
            }
            
            if (Files.exists(configFile)) {
                logger.info("  config.yaml found at: {}", configFile);
                try {
                    List<String> lines = Files.readAllLines(configFile);
                    logger.info("  config.yaml contents ({} lines):", lines.size());
                    for (String line : lines) {
                        logger.info("    {}", line);
                    }
                } catch (IOException e) {
                    logger.warn("    Error reading config.yaml: {}", e.getMessage());
                }
            } else {
                logger.warn("  config.yaml NOT FOUND at: {}", configFile);
                
                // Check alternative locations
                Path appConfigDir = Paths.get("/home/vcap/app/.config/goose");
                if (Files.exists(appConfigDir)) {
                    logger.info("  Found config at /home/vcap/app/.config/goose/ (needs to be copied to HOME)");
                    try {
                        Files.list(appConfigDir).forEach(path -> 
                            logger.info("    File: {}", path.getFileName()));
                    } catch (IOException e) {
                        logger.warn("    Error listing app config directory: {}", e.getMessage());
                    }
                }
            }
            
            if (Files.exists(profilesFile)) {
                logger.info("  profiles.yaml found at: {}", profilesFile);
            } else {
                logger.warn("  profiles.yaml NOT FOUND at: {}", profilesFile);
            }
        }
    }

    /**
     * Constructs a new executor with explicit configuration.
     *
     * @param goosePath path to the Goose CLI executable
     * @param apiKeys map of provider API keys (e.g., ANTHROPIC_API_KEY -> value)
     * @throws IllegalArgumentException if goosePath is null or empty
     */
    public GooseExecutorImpl(String goosePath, Map<String, String> apiKeys) {
        if (goosePath == null || goosePath.isEmpty()) {
            throw new IllegalArgumentException("Goose CLI path cannot be null or empty");
        }
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one API key must be provided");
        }

        this.goosePath = goosePath;
        this.baseEnvironment = buildEnvironment(apiKeys);
    }

    @Override
    public String execute(String prompt) {
        return execute(prompt, GooseOptions.defaults());
    }

    @Override
    public String execute(String prompt, GooseOptions options) {
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        logger.debug("Executing Goose with prompt length: {}", prompt.length());

        List<String> command = buildCommand(prompt, options);
        ProcessBuilder pb = new ProcessBuilder(command);

        // Set working directory if specified
        if (options.workingDirectory() != null) {
            pb.directory(options.workingDirectory().toFile());
        }

        // Add environment variables to subprocess
        Map<String, String> env = pb.environment();
        env.putAll(baseEnvironment);
        env.putAll(options.additionalEnv());

        // CRITICAL: Redirect stderr to stdout to prevent buffer deadlock
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // CRITICAL: Close stdin immediately so CLI doesn't wait for input
            process.getOutputStream().close();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("Goose output: {}", line);
                }
            }

            // Wait for process to complete with timeout
            boolean finished = process.waitFor(
                    options.timeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            if (!finished) {
                logger.error("Goose command timed out after {}", options.timeout());
                process.destroyForcibly();
                throw new TimeoutException("Goose execution timed out after " + options.timeout());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String outputStr = output.toString();
                logger.error("Goose failed with exit code: {}, output: {}", exitCode, outputStr);
                throw new GooseExecutionException(
                        "Goose failed with exit code: " + exitCode,
                        exitCode,
                        outputStr
                );
            }

            String result = output.toString();
            logger.info("Goose executed successfully, output length: {}", result.length());
            return result;

        } catch (IOException e) {
            logger.error("Failed to execute Goose", e);
            throw new GooseExecutionException("Failed to execute Goose", e);
        } catch (InterruptedException e) {
            logger.error("Goose execution interrupted", e);
            Thread.currentThread().interrupt();
            throw new GooseExecutionException("Goose execution interrupted", e);
        } catch (TimeoutException e) {
            throw new GooseExecutionException("Goose execution timed out", e);
        }
    }

    @Override
    public CompletableFuture<String> executeAsync(String prompt) {
        return executeAsync(prompt, GooseOptions.defaults());
    }

    @Override
    public CompletableFuture<String> executeAsync(String prompt, GooseOptions options) {
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        return CompletableFuture.supplyAsync(() -> execute(prompt, options));
    }

    @Override
    public Stream<String> executeStreaming(String prompt) {
        return executeStreaming(prompt, GooseOptions.defaults());
    }

    @Override
    public Stream<String> executeStreaming(String prompt, GooseOptions options) {
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        logger.debug("Starting streaming execution with prompt length: {}", prompt.length());

        List<String> command = buildCommand(prompt, options);
        ProcessBuilder pb = new ProcessBuilder(command);

        // Set working directory if specified
        if (options.workingDirectory() != null) {
            pb.directory(options.workingDirectory().toFile());
        }

        // Add environment variables to subprocess
        Map<String, String> env = pb.environment();
        env.putAll(baseEnvironment);
        env.putAll(options.additionalEnv());

        // CRITICAL: Redirect stderr to stdout to prevent buffer deadlock
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // CRITICAL: Close stdin immediately so CLI doesn't wait for input
            process.getOutputStream().close();

            // Create streaming handle for resource management
            StreamingProcessHandle handle = new StreamingProcessHandle(process, options.timeout());

            // Create stream that reads lines as they arrive
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            // Return stream with proper cleanup handlers
            return reader.lines()
                    .onClose(() -> {
                        logger.debug("Stream closed, cleaning up resources");
                        handle.close();
                        try {
                            reader.close();
                        } catch (IOException e) {
                            logger.warn("Error closing reader", e);
                        }
                    });

        } catch (IOException e) {
            logger.error("Failed to start streaming execution", e);
            throw new GooseExecutionException("Failed to start Goose streaming", e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check if CLI path environment variable is set
            if (goosePath == null || goosePath.isEmpty()) {
                logger.warn("GOOSE_CLI_PATH not set");
                return false;
            }

            // Check if CLI executable exists
            Path cliPath = Paths.get(goosePath);
            if (!Files.exists(cliPath)) {
                logger.warn("Goose CLI executable not found at: {}", goosePath);
                return false;
            }

            // Check if at least one API key is set
            if (!hasProviderCredentials()) {
                logger.warn("No LLM provider credentials found");
                return false;
            }

            logger.debug("Goose CLI is available");
            return true;

        } catch (Exception e) {
            logger.error("Error checking Goose availability", e);
            return false;
        }
    }

    @Override
    public String getVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder(goosePath, "--version");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() == 0) {
                return output.toString().trim();
            }

            return null;

        } catch (Exception e) {
            logger.error("Failed to get Goose version", e);
            return null;
        }
    }

    // ==================== Session Management ====================

    @Override
    public String executeInSession(String sessionName, String prompt, boolean resume) {
        return executeInSession(sessionName, prompt, resume, GooseOptions.defaults());
    }

    @Override
    public String executeInSession(String sessionName, String prompt, boolean resume, GooseOptions options) {
        validateSessionName(sessionName);
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        logger.debug("Executing Goose in session '{}', resume={}, prompt length: {}", 
            sessionName, resume, prompt.length());

        List<String> command = buildSessionCommand(sessionName, prompt, resume, options);
        ProcessBuilder pb = new ProcessBuilder(command);

        // Set working directory if specified
        if (options.workingDirectory() != null) {
            pb.directory(options.workingDirectory().toFile());
        }

        // Add environment variables to subprocess
        Map<String, String> env = pb.environment();
        env.putAll(baseEnvironment);
        env.putAll(options.additionalEnv());

        // CRITICAL: Redirect stderr to stdout to prevent buffer deadlock
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // CRITICAL: Close stdin immediately so CLI doesn't wait for input
            process.getOutputStream().close();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("Goose session output: {}", line);
                }
            }

            // Wait for process to complete with timeout
            boolean finished = process.waitFor(
                    options.timeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            if (!finished) {
                logger.error("Goose session command timed out after {}", options.timeout());
                process.destroyForcibly();
                throw new TimeoutException("Goose session execution timed out after " + options.timeout());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String outputStr = output.toString();
                logger.error("Goose session failed with exit code: {}, output: {}", exitCode, outputStr);
                throw new GooseExecutionException(
                        "Goose session failed with exit code: " + exitCode,
                        exitCode,
                        outputStr
                );
            }

            String result = filterGooseBanner(output.toString());
            logger.info("Goose session '{}' executed successfully, output length: {}", sessionName, result.length());
            return result;

        } catch (IOException e) {
            logger.error("Failed to execute Goose session", e);
            throw new GooseExecutionException("Failed to execute Goose session", e);
        } catch (InterruptedException e) {
            logger.error("Goose session execution interrupted", e);
            Thread.currentThread().interrupt();
            throw new GooseExecutionException("Goose session execution interrupted", e);
        } catch (TimeoutException e) {
            throw new GooseExecutionException("Goose session execution timed out", e);
        }
    }

    @Override
    public Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume) {
        return executeInSessionStreaming(sessionName, prompt, resume, GooseOptions.defaults());
    }

    @Override
    public Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume, GooseOptions options) {
        validateSessionName(sessionName);
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        logger.debug("Starting streaming session '{}', resume={}, prompt length: {}", 
            sessionName, resume, prompt.length());

        List<String> command = buildSessionCommand(sessionName, prompt, resume, options);
        ProcessBuilder pb = new ProcessBuilder(command);

        // Set working directory if specified
        if (options.workingDirectory() != null) {
            pb.directory(options.workingDirectory().toFile());
        }

        // Add environment variables to subprocess
        Map<String, String> env = pb.environment();
        env.putAll(baseEnvironment);
        env.putAll(options.additionalEnv());

        // CRITICAL: Redirect stderr to stdout to prevent buffer deadlock
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // CRITICAL: Close stdin immediately so CLI doesn't wait for input
            process.getOutputStream().close();

            // Create streaming handle for resource management
            StreamingProcessHandle handle = new StreamingProcessHandle(process, options.timeout());

            // Create stream that reads lines as they arrive
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );

            // Return stream with proper cleanup handlers, filtering out banner lines
            return reader.lines()
                    .filter(line -> !isGooseBannerLine(line))
                    .onClose(() -> {
                        logger.debug("Session stream closed, cleaning up resources");
                        handle.close();
                        try {
                            reader.close();
                        } catch (IOException e) {
                            logger.warn("Error closing reader", e);
                        }
                    });

        } catch (IOException e) {
            logger.error("Failed to start streaming session execution", e);
            throw new GooseExecutionException("Failed to start Goose streaming session", e);
        }
    }

    // ==================== Private Utility Methods ====================

    /**
     * Build the command line arguments for Goose CLI.
     * <p>
     * Goose uses: goose session --text "prompt" --max-turns N
     * </p>
     */
    private List<String> buildCommand(String prompt, GooseOptions options) {
        List<String> command = new ArrayList<>();
        command.add(goosePath);
        command.add("session");
        command.add("--text");
        command.add(prompt);

        // Add max-turns to prevent runaway sessions
        command.add("--max-turns");
        command.add(String.valueOf(options.maxTurns()));

        return command;
    }

    /**
     * Build the command line arguments for a named Goose session.
     * <p>
     * For new sessions: goose run -n "session-name" --provider X --model Y -t "prompt" --max-turns N
     * For resuming:     goose run --resume -n "session-name" -t "prompt" --max-turns N
     * </p>
     * <p>
     * Note: We use "goose run" instead of "goose session" because:
     * - "goose session" starts an interactive REPL that waits for user input
     * - "goose run" executes a task non-interactively and exits when complete
     * </p>
     * <p>
     * IMPORTANT: Extensions are configured in ~/.config/goose/config.yaml (generated by buildpack),
     * NOT via CLI flags. Sessions started with --with-streamable-http-extension CLI flags cannot
     * be resumed (Goose returns "Resume cancelled"). Extensions in config.yaml work correctly.
     * </p>
     * <p>
     * Provider and model flags can be passed on resume if needed - they work correctly.
     * </p>
     */
    private List<String> buildSessionCommand(String sessionName, String prompt, boolean resume, GooseOptions options) {
        logger.info("Building session command: session='{}', resume={}, promptLength={}", 
            sessionName, resume, prompt.length());
        
        List<String> command = new ArrayList<>();
        command.add(goosePath);
        command.add("run");
        
        // Add session name
        command.add("-n");
        command.add(sessionName);
        
        // Add resume flag if continuing an existing session
        if (resume) {
            command.add("-r");
            logger.info("Session '{}': Adding resume flag (-r)", sessionName);
        } else {
            logger.info("Session '{}': New session (no resume flag)", sessionName);
        }
        
        // Resolve provider: options > GOOSE_PROVIDER env var
        // Provider and model can be passed on all invocations (new and resume) - they work correctly
        String provider = options.provider();
        if (provider == null || provider.isEmpty()) {
            provider = System.getenv("GOOSE_PROVIDER");
        }
        if (provider != null && !provider.isEmpty()) {
            command.add("--provider");
            command.add(provider);
            logger.debug("Session '{}': Using provider '{}'", sessionName, provider);
        }
        
        // Resolve model: options > GOOSE_MODEL env var
        String model = options.model();
        if (model == null || model.isEmpty()) {
            model = System.getenv("GOOSE_MODEL");
        }
        if (model != null && !model.isEmpty()) {
            command.add("--model");
            command.add(model);
            logger.debug("Session '{}': Using model '{}'", sessionName, model);
        }
        
        // NOTE: Extensions are configured in ~/.config/goose/config.yaml by the buildpack.
        // We do NOT pass --with-streamable-http-extension flags via CLI because:
        // 1. Sessions started with --with-streamable-http-extension CLI flags CANNOT be resumed
        //    (Goose returns "Resume cancelled" regardless of whether you pass the same flags)
        // 2. Extensions configured in config.yaml are loaded automatically and work with resume
        // See: https://block.github.io/goose/docs/guides/configuration-files/
        logger.info("Session '{}': Extensions loaded from config.yaml (not via CLI flags)", sessionName);
        
        // Add the prompt using -t flag
        command.add("-t");
        command.add(prompt);

        // Add max-turns to prevent runaway sessions
        command.add("--max-turns");
        command.add(String.valueOf(options.maxTurns()));

        // Log the full command (with prompt truncated for readability)
        String promptPreview = prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt;
        logger.info("Session '{}': Full command: {} (prompt: '{}')", 
            sessionName, 
            String.join(" ", command.subList(0, command.size() - 3)), // Exclude -t, prompt, --max-turns, N
            promptPreview);

        return command;
    }
    
    // NOTE: Extension loading via CLI flags (--with-streamable-http-extension) has been removed.
    // Extensions are now configured in ~/.config/goose/config.yaml by the buildpack.
    // This is required because Goose CLI has a bug where sessions started with 
    // --with-streamable-http-extension flags cannot be resumed (always returns "Resume cancelled").
    // Extensions in config.yaml work correctly with session resume.

    /**
     * Validate session name is not null or empty.
     */
    private void validateSessionName(String sessionName) {
        if (sessionName == null || sessionName.trim().isEmpty()) {
            throw new IllegalArgumentException("Session name cannot be null or empty");
        }
    }

    /**
     * Check if a line is part of the Goose CLI startup banner.
     * <p>
     * The Goose CLI outputs a startup banner like:
     * <pre>
     * starting session | provider: openai model: gpt-4o
     *    session id: 20260108_1
     *    working directory: /home/vcap/app
     * </pre>
     * Or for resumed sessions:
     * <pre>
     * resuming session | provider: openai model: gpt-4o
     * </pre>
     * </p>
     */
    private boolean isGooseBannerLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("starting session |") ||
               trimmed.startsWith("resuming session |") ||
               trimmed.startsWith("session id:") ||
               trimmed.startsWith("working directory:");
    }

    /**
     * Filter out Goose CLI banner/startup lines from output.
     * The banner appears at the start of each command invocation and includes:
     * - "starting session | ..." or "resuming session | ..."
     * - "session id: ..."
     * - "working directory: ..."
     */
    private String filterGooseBanner(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        
        StringBuilder filtered = new StringBuilder();
        String[] lines = output.split("\n");
        boolean inBanner = true;
        
        for (String line : lines) {
            if (inBanner) {
                // Skip banner lines and empty lines at the start
                if (isGooseBannerLine(line) || line.trim().isEmpty()) {
                    continue;
                }
                // First non-banner, non-empty line - we're past the banner
                inBanner = false;
            }
            
            filtered.append(line).append("\n");
        }
        
        // Trim trailing newline
        String result = filtered.toString();
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        
        return result;
    }

    /**
     * Build base environment variables from system environment.
     */
    private Map<String, String> buildBaseEnvironment() {
        Map<String, String> apiKeys = new HashMap<>();

        // Collect all provider API keys
        copyEnvIfPresent(apiKeys, "ANTHROPIC_API_KEY");
        copyEnvIfPresent(apiKeys, "OPENAI_API_KEY");
        copyEnvIfPresent(apiKeys, "GOOGLE_API_KEY");
        copyEnvIfPresent(apiKeys, "DATABRICKS_HOST");
        copyEnvIfPresent(apiKeys, "DATABRICKS_TOKEN");
        copyEnvIfPresent(apiKeys, "OLLAMA_HOST");

        if (!hasProviderCredentials()) {
            logger.warn("No LLM provider credentials found in environment");
        }

        return buildEnvironment(apiKeys);
    }

    /**
     * Build environment variables with explicit API keys.
     */
    private Map<String, String> buildEnvironment(Map<String, String> apiKeys) {
        Map<String, String> env = new HashMap<>(apiKeys);

        // Pass HOME directory (needed for Goose config)
        String home = System.getenv("HOME");
        if (home != null && !home.isEmpty()) {
            env.put("HOME", home);
        }

        // Pass Goose provider/model settings if set
        copyEnvIfPresent(env, "GOOSE_PROVIDER");
        copyEnvIfPresent(env, "GOOSE_MODEL");
        copyEnvIfPresent(env, "GOOSE_PROVIDER__TYPE");
        copyEnvIfPresent(env, "GOOSE_PROVIDER__MODEL");

        return env;
    }

    /**
     * Copy environment variable if present.
     */
    private void copyEnvIfPresent(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            env.put(key, value);
        }
    }

    /**
     * Check if any provider credentials are available.
     */
    private boolean hasProviderCredentials() {
        return isEnvSet("ANTHROPIC_API_KEY") ||
                isEnvSet("OPENAI_API_KEY") ||
                isEnvSet("GOOGLE_API_KEY") ||
                (isEnvSet("DATABRICKS_HOST") && isEnvSet("DATABRICKS_TOKEN")) ||
                isEnvSet("OLLAMA_HOST");
    }

    /**
     * Check if an environment variable is set and non-empty.
     */
    private boolean isEnvSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty();
    }

    /**
     * Get a required environment variable or throw exception.
     */
    private String getRequiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    String.format("%s environment variable is not set", name)
            );
        }
        return value;
    }

    /**
     * Validate prompt is not null or empty.
     */
    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt cannot be null or empty");
        }
    }

    /**
     * Inner class to manage streaming process lifecycle and timeout enforcement.
     */
    private static class StreamingProcessHandle implements AutoCloseable {
        private final Process process;
        private final ScheduledFuture<?> timeoutTask;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public StreamingProcessHandle(Process process, java.time.Duration timeout) {
            this.process = process;

            // Schedule timeout task to forcibly destroy process if it runs too long
            this.timeoutTask = timeoutExecutor.schedule(() -> {
                if (process.isAlive()) {
                    logger.warn("Streaming process timed out after {}, forcibly destroying", timeout);
                    process.destroyForcibly();
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            logger.debug("Created streaming process handle with timeout: {}", timeout);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                logger.debug("Closing streaming process handle");

                // Cancel timeout task
                timeoutTask.cancel(false);

                // Destroy process if still alive
                if (process.isAlive()) {
                    logger.debug("Process still alive, destroying gracefully");
                    process.destroy();

                    // Give it a moment to terminate gracefully
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
}

