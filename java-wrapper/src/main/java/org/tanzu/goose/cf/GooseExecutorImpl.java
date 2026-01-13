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
import java.util.stream.Stream;

/**
 * Default implementation of {@link GooseExecutor}.
 * <p>
 * This implementation uses {@link ProcessBuilder} to invoke the Goose CLI
 * and follows best practices for process management in Cloud Foundry environments.
 * </p>
 *
 * <h2>Critical Implementation Details</h2>
 * <ul>
 *   <li>Always closes stdin immediately to prevent CLI from waiting for input</li>
 *   <li>Redirects stderr to stdout to prevent buffer deadlock</li>
 *   <li>Passes environment variables to subprocess explicitly</li>
 *   <li>Uses timeouts to prevent indefinite hangs</li>
 *   <li>Properly cleans up resources</li>
 * </ul>
 *
 * <h2>SSE Normalization for GenAI Proxies</h2>
 * <p>
 * When using OpenAI-compatible endpoints (GenAI services), this executor automatically
 * routes requests through a local {@link SseNormalizingProxy} to fix SSE format
 * incompatibilities.
 * </p>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 * @see GooseCommandBuilder
 * @see GooseEnvironmentManager
 * @see GooseConfigurationParser
 */
public class GooseExecutorImpl implements GooseExecutor, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GooseExecutorImpl.class);

    private final String goosePath;
    private final GooseCommandBuilder commandBuilder;
    private final GooseEnvironmentManager environmentManager;
    private final GooseConfigurationParser configurationParser;
    
    // Cached configuration (lazy-loaded)
    private volatile GooseConfiguration cachedConfiguration;

    /**
     * Constructs a new executor using environment variables.
     *
     * @throws IllegalStateException if GOOSE_CLI_PATH is not set
     */
    public GooseExecutorImpl() {
        this.goosePath = getRequiredEnv("GOOSE_CLI_PATH");
        this.commandBuilder = new GooseCommandBuilder(goosePath);
        this.environmentManager = new GooseEnvironmentManager();
        this.configurationParser = new GooseConfigurationParser();
        
        logGooseConfiguration();
    }

    /**
     * Constructs a new executor with explicit configuration.
     *
     * @param goosePath path to the Goose CLI executable
     * @param apiKeys map of provider API keys
     */
    public GooseExecutorImpl(String goosePath, Map<String, String> apiKeys) {
        if (goosePath == null || goosePath.isEmpty()) {
            throw new IllegalArgumentException("Goose CLI path cannot be null or empty");
        }
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one API key must be provided");
        }

        this.goosePath = goosePath;
        this.commandBuilder = new GooseCommandBuilder(goosePath);
        this.environmentManager = new GooseEnvironmentManager(apiKeys);
        this.configurationParser = new GooseConfigurationParser();
    }

    // ==================== Single-Shot Execution ====================

    @Override
    public String execute(String prompt) {
        return execute(prompt, GooseOptions.defaults());
    }

    @Override
    public String execute(String prompt, GooseOptions options) {
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        logger.debug("Executing Goose with prompt length: {}", prompt.length());

        List<String> command = commandBuilder.buildSingleShotCommand(prompt, options);
        return executeProcess(command, options, "Goose");
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

        List<String> command = commandBuilder.buildSingleShotCommand(prompt, options);
        return executeStreamingProcess(command, options);
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

        List<String> command = commandBuilder.buildSessionCommand(sessionName, prompt, resume, options);
        String result = executeProcess(command, options, "Goose session");
        return GooseOutputFilter.filterBanner(result);
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

        List<String> command = commandBuilder.buildSessionCommand(sessionName, prompt, resume, options);
        return executeStreamingProcess(command, options)
                .filter(line -> !GooseOutputFilter.isBannerLine(line));
    }

    // ==================== Streaming JSON Support ====================

    @Override
    public Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume) {
        return executeInSessionStreamingJson(sessionName, prompt, resume, GooseOptions.defaults());
    }

    @Override
    public Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume, GooseOptions options) {
        validateSessionName(sessionName);
        validatePrompt(prompt);
        Objects.requireNonNull(options, "Options cannot be null");

        logger.debug("Starting streaming JSON session '{}', resume={}, prompt length: {}", 
            sessionName, resume, prompt.length());

        List<String> command = commandBuilder.buildStreamingJsonCommand(sessionName, prompt, resume, options);
        ProcessBuilder pb = createProcessBuilder(command, options);
        
        // Enable debug mode
        Map<String, String> env = pb.environment();
        env.put("GOOSE_DEBUG", "true");
        env.put("RUST_LOG", "goose=debug,goose_cli=debug");
        
        logEnvironmentDetails(sessionName, env);

        try {
            Process process = pb.start();
            process.getOutputStream().close();

            StreamingProcessHandle handle = new StreamingProcessHandle(process, options.timeout());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            return reader.lines()
                    .peek(line -> logger.debug("Goose raw output: {}", line))
                    .filter(line -> line.startsWith("{"))
                    .onClose(() -> {
                        logger.info("Streaming JSON session closed, cleaning up resources");
                        handle.close();
                        closeQuietly(reader);
                        if (!process.isAlive()) {
                            logger.info("Goose process exited with code: {}", process.exitValue());
                        }
                    });

        } catch (IOException e) {
            logger.error("Failed to start streaming JSON session execution", e);
            throw new GooseExecutionException("Failed to start Goose streaming JSON session", e);
        }
    }

    // ==================== Status Methods ====================

    @Override
    public boolean isAvailable() {
        try {
            if (goosePath == null || goosePath.isEmpty()) {
                logger.warn("GOOSE_CLI_PATH not set");
                return false;
            }

            Path cliPath = Paths.get(goosePath);
            if (!Files.exists(cliPath)) {
                logger.warn("Goose CLI executable not found at: {}", goosePath);
                return false;
            }

            if (!environmentManager.hasProviderCredentials()) {
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
            ProcessBuilder pb = new ProcessBuilder(commandBuilder.buildVersionCommand());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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

    @Override
    public GooseConfiguration getConfiguration() {
        if (cachedConfiguration != null) {
            return cachedConfiguration;
        }
        
        synchronized (this) {
            if (cachedConfiguration != null) {
                return cachedConfiguration;
            }
            
            cachedConfiguration = configurationParser.parse();
            return cachedConfiguration;
        }
    }

    @Override
    public void close() {
        environmentManager.close();
    }

    // ==================== Private Helper Methods ====================

    /**
     * Executes a process and returns the output as a string.
     */
    private String executeProcess(List<String> command, GooseOptions options, String processName) {
        ProcessBuilder pb = createProcessBuilder(command, options);

        try {
            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("{} output: {}", processName, line);
                }
            }

            boolean finished = process.waitFor(options.timeout().toMillis(), TimeUnit.MILLISECONDS);

            if (!finished) {
                logger.error("{} command timed out after {}", processName, options.timeout());
                process.destroyForcibly();
                throw new TimeoutException(processName + " execution timed out after " + options.timeout());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String outputStr = output.toString();
                logger.error("{} failed with exit code: {}, output: {}", processName, exitCode, outputStr);
                throw new GooseExecutionException(
                        processName + " failed with exit code: " + exitCode,
                        exitCode,
                        outputStr
                );
            }

            String result = output.toString();
            logger.info("{} executed successfully, output length: {}", processName, result.length());
            return result;

        } catch (IOException e) {
            logger.error("Failed to execute {}", processName, e);
            throw new GooseExecutionException("Failed to execute " + processName, e);
        } catch (InterruptedException e) {
            logger.error("{} execution interrupted", processName, e);
            Thread.currentThread().interrupt();
            throw new GooseExecutionException(processName + " execution interrupted", e);
        } catch (TimeoutException e) {
            throw new GooseExecutionException(processName + " execution timed out", e);
        }
    }

    /**
     * Executes a process and returns output as a stream.
     */
    private Stream<String> executeStreamingProcess(List<String> command, GooseOptions options) {
        ProcessBuilder pb = createProcessBuilder(command, options);

        try {
            Process process = pb.start();
            process.getOutputStream().close();

            StreamingProcessHandle handle = new StreamingProcessHandle(process, options.timeout());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            return reader.lines()
                    .onClose(() -> {
                        logger.debug("Stream closed, cleaning up resources");
                        handle.close();
                        closeQuietly(reader);
                    });

        } catch (IOException e) {
            logger.error("Failed to start streaming execution", e);
            throw new GooseExecutionException("Failed to start Goose streaming", e);
        }
    }

    /**
     * Creates a configured ProcessBuilder.
     */
    private ProcessBuilder createProcessBuilder(List<String> command, GooseOptions options) {
        ProcessBuilder pb = new ProcessBuilder(command);

        if (options.workingDirectory() != null) {
            pb.directory(options.workingDirectory().toFile());
        }

        environmentManager.applyToProcessEnvironment(pb.environment(), options);
        pb.redirectErrorStream(true);

        return pb;
    }

    /**
     * Log Goose configuration status for troubleshooting.
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
     * Log environment details for debugging.
     */
    private void logEnvironmentDetails(String sessionName, Map<String, String> env) {
        logger.info("Session '{}': Subprocess env (FULL):", sessionName);
        logger.info("  OPENAI_API_KEY: present={}, length={}, first3chars={}", 
            env.containsKey("OPENAI_API_KEY"),
            env.get("OPENAI_API_KEY") != null ? env.get("OPENAI_API_KEY").length() : 0,
            env.get("OPENAI_API_KEY") != null && env.get("OPENAI_API_KEY").length() >= 3 
                ? env.get("OPENAI_API_KEY").substring(0, 3) + "..." : "null");
        logger.info("  OPENAI_HOST={}", env.get("OPENAI_HOST"));
        logger.info("  OPENAI_BASE_PATH={}", env.get("OPENAI_BASE_PATH"));
        logger.info("  GOOSE_PROVIDER={}", env.get("GOOSE_PROVIDER"));
        logger.info("  GOOSE_MODEL={}", env.get("GOOSE_MODEL"));
        logger.info("  GOOSE_DEBUG={}", env.get("GOOSE_DEBUG"));
        logger.info("  HOME={}", env.get("HOME"));
        
        String apiKey = env.get("OPENAI_API_KEY");
        if (apiKey != null && apiKey.length() > 10) {
            String prefix = apiKey.substring(0, Math.min(10, apiKey.length()));
            logger.info("  OPENAI_API_KEY prefix: {} (is_jwt={})", 
                prefix + "...", 
                apiKey.startsWith("ey"));
        }
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
     * Validate session name is not null or empty.
     */
    private void validateSessionName(String sessionName) {
        if (sessionName == null || sessionName.trim().isEmpty()) {
            throw new IllegalArgumentException("Session name cannot be null or empty");
        }
    }

    /**
     * Close a reader quietly, logging any errors.
     */
    private void closeQuietly(BufferedReader reader) {
        try {
            reader.close();
        } catch (IOException e) {
            logger.warn("Error closing reader", e);
        }
    }
}
