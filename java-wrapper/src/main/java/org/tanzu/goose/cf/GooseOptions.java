package org.tanzu.goose.cf;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Options for configuring Goose CLI execution.
 * <p>
 * Use the builder pattern to create instances:
 * </p>
 * <pre>{@code
 * GooseOptions options = GooseOptions.builder()
 *     .timeout(Duration.ofMinutes(10))
 *     .model("claude-sonnet-4-20250514")
 *     .maxTurns(50)
 *     .build();
 * }</pre>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public record GooseOptions(
        Duration timeout,
        String model,
        String provider,
        int maxTurns,
        Path workingDirectory,
        Map<String, String> additionalEnv
) {

    /**
     * Default timeout for Goose execution (5 minutes).
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Default maximum number of conversation turns.
     */
    public static final int DEFAULT_MAX_TURNS = 100;

    /**
     * Create options with default values.
     *
     * @return default GooseOptions
     */
    public static GooseOptions defaults() {
        return new GooseOptions(
                DEFAULT_TIMEOUT,
                null,
                null,
                DEFAULT_MAX_TURNS,
                null,
                Map.of()
        );
    }

    /**
     * Create a builder for GooseOptions.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for GooseOptions.
     */
    public static class Builder {
        private Duration timeout = DEFAULT_TIMEOUT;
        private String model;
        private String provider;
        private int maxTurns = DEFAULT_MAX_TURNS;
        private Path workingDirectory;
        private final Map<String, String> additionalEnv = new HashMap<>();

        /**
         * Set the execution timeout.
         *
         * @param timeout the maximum duration to wait for Goose to complete
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Set the LLM model to use.
         * <p>
         * Examples: "claude-sonnet-4-20250514", "gpt-4", "gemini-pro"
         * </p>
         *
         * @param model the model identifier
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Set the LLM provider to use.
         * <p>
         * Supported providers: "anthropic", "openai", "google", "databricks", "ollama"
         * </p>
         *
         * @param provider the provider identifier
         * @return this builder
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Set the maximum number of conversation turns.
         * <p>
         * This prevents runaway sessions. Default is 100.
         * </p>
         *
         * @param maxTurns the maximum number of turns
         * @return this builder
         */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Set the working directory for Goose execution.
         * <p>
         * If not specified, defaults to the current working directory.
         * </p>
         *
         * @param workingDirectory the directory to execute in
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Add an additional environment variable for the Goose process.
         *
         * @param key the environment variable name
         * @param value the environment variable value
         * @return this builder
         */
        public Builder addEnv(String key, String value) {
            this.additionalEnv.put(key, value);
            return this;
        }

        /**
         * Add multiple environment variables for the Goose process.
         *
         * @param env the environment variables to add
         * @return this builder
         */
        public Builder addEnv(Map<String, String> env) {
            this.additionalEnv.putAll(env);
            return this;
        }

        /**
         * Build the GooseOptions instance.
         *
         * @return a new GooseOptions instance
         */
        public GooseOptions build() {
            return new GooseOptions(
                    timeout,
                    model,
                    provider,
                    maxTurns,
                    workingDirectory,
                    Map.copyOf(additionalEnv)
            );
        }
    }
}

