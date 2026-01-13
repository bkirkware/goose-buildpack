package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages environment variables for Goose CLI subprocess execution.
 * <p>
 * Handles:
 * </p>
 * <ul>
 *   <li>Collection of LLM provider API keys from system environment</li>
 *   <li>Application of OpenAI-compatible endpoint configuration</li>
 *   <li>SSE normalizing proxy integration for GenAI services</li>
 * </ul>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class GooseEnvironmentManager {

    private static final Logger logger = LoggerFactory.getLogger(GooseEnvironmentManager.class);

    private final Map<String, String> baseEnvironment;
    
    // SSE normalizing proxy for GenAI compatibility (lazy-initialized)
    private volatile SseNormalizingProxy sseProxy;
    private final Object proxyLock = new Object();

    /**
     * Constructs an environment manager using system environment variables.
     */
    public GooseEnvironmentManager() {
        this.baseEnvironment = buildBaseEnvironment();
    }

    /**
     * Constructs an environment manager with explicit API keys.
     *
     * @param apiKeys map of provider API keys (e.g., ANTHROPIC_API_KEY -> value)
     */
    public GooseEnvironmentManager(Map<String, String> apiKeys) {
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one API key must be provided");
        }
        this.baseEnvironment = buildEnvironment(apiKeys);
    }

    /**
     * Gets the base environment variables to pass to Goose subprocess.
     *
     * @return unmodifiable map of environment variables
     */
    public Map<String, String> getBaseEnvironment() {
        return Map.copyOf(baseEnvironment);
    }

    /**
     * Applies environment variables to a subprocess environment map.
     *
     * @param env the environment map to update
     * @param options the execution options
     */
    public void applyToProcessEnvironment(Map<String, String> env, GooseOptions options) {
        env.putAll(baseEnvironment);
        env.putAll(options.additionalEnv());
        applyOpenAiCompatibleEnv(env, options);
    }

    /**
     * Checks if any provider credentials are available.
     *
     * @return true if at least one provider is configured
     */
    public boolean hasProviderCredentials() {
        return isEnvSet("ANTHROPIC_API_KEY") ||
                isEnvSet("OPENAI_API_KEY") ||
                isEnvSet("GOOGLE_API_KEY") ||
                (isEnvSet("DATABRICKS_HOST") && isEnvSet("DATABRICKS_TOKEN")) ||
                isEnvSet("OLLAMA_HOST");
    }

    /**
     * Stops the SSE proxy if running and releases resources.
     */
    public void close() {
        synchronized (proxyLock) {
            if (sseProxy != null) {
                logger.info("Closing SSE normalizing proxy");
                sseProxy.stop();
                sseProxy = null;
            }
        }
    }

    /**
     * Apply OpenAI-compatible environment variables from GooseOptions.
     * <p>
     * When using OpenAI-compatible endpoints (e.g., GenAI services), the API key
     * and base URL need to be passed as environment variables to the Goose CLI.
     * </p>
     * <p>
     * <strong>SSE Normalization:</strong> When a custom base URL is specified,
     * this method automatically routes requests through an {@link SseNormalizingProxy}
     * to fix SSE format incompatibilities with some GenAI proxies.
     * </p>
     */
    private void applyOpenAiCompatibleEnv(Map<String, String> env, GooseOptions options) {
        boolean hasApiKey = options.apiKey() != null && !options.apiKey().isEmpty();
        boolean hasBaseUrl = options.baseUrl() != null && !options.baseUrl().isEmpty();
        
        String existingOpenAiKey = env.get("OPENAI_API_KEY");
        String existingOpenAiHost = env.get("OPENAI_HOST");
        logger.info("Before apply - OPENAI_API_KEY present: {}, OPENAI_HOST: {}", 
                existingOpenAiKey != null, existingOpenAiHost);
        
        if (hasApiKey && hasBaseUrl) {
            String proxyUrl = getOrCreateSseProxy(options.baseUrl(), options.apiKey());
            
            if (proxyUrl != null) {
                env.put("OPENAI_HOST", proxyUrl);
                env.put("OPENAI_API_KEY", "sk-proxy-handled-by-sse-normalizing-proxy");
                logger.info("Using SSE normalizing proxy: {} -> {}", proxyUrl, options.baseUrl());
            } else {
                env.put("OPENAI_API_KEY", options.apiKey());
                env.put("OPENAI_HOST", options.baseUrl());
                logger.warn("SSE proxy unavailable, using direct connection to: {}", options.baseUrl());
            }
        } else if (hasApiKey) {
            env.put("OPENAI_API_KEY", options.apiKey());
            logger.info("Applied OPENAI_API_KEY from GooseOptions (length: {})", options.apiKey().length());
        } else if (hasBaseUrl) {
            env.put("OPENAI_HOST", options.baseUrl());
            logger.info("Applied OPENAI_HOST from GooseOptions: {}", options.baseUrl());
        } else {
            logger.debug("No OPENAI_API_KEY or OPENAI_HOST in GooseOptions, using environment defaults");
        }
        
        logger.info("After apply - OPENAI_HOST in env: {}", env.get("OPENAI_HOST"));
    }
    
    /**
     * Gets or creates the SSE normalizing proxy for the given upstream URL.
     */
    private String getOrCreateSseProxy(String upstreamUrl, String apiKey) {
        synchronized (proxyLock) {
            if (sseProxy != null && sseProxy.isRunning()) {
                return sseProxy.getProxyUrl();
            }
            
            try {
                sseProxy = new SseNormalizingProxy(upstreamUrl, apiKey);
                sseProxy.start();
                
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (sseProxy != null) {
                        sseProxy.stop();
                    }
                }, "sse-proxy-shutdown"));
                
                return sseProxy.getProxyUrl();
            } catch (Exception e) {
                logger.error("Failed to start SSE normalizing proxy", e);
                return null;
            }
        }
    }

    /**
     * Build base environment variables from system environment.
     */
    private Map<String, String> buildBaseEnvironment() {
        Map<String, String> apiKeys = new HashMap<>();

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

        String home = System.getenv("HOME");
        if (home != null && !home.isEmpty()) {
            env.put("HOME", home);
        }

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
     * Check if an environment variable is set and non-empty.
     */
    private boolean isEnvSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty();
    }
}
