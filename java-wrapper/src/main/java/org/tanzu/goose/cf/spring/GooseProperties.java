package org.tanzu.goose.cf.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Goose CLI integration.
 * <p>
 * These properties can be set in {@code application.yml} or {@code application.properties}:
 * </p>
 * <pre>{@code
 * goose:
 *   enabled: true
 *   timeout: 5m
 *   max-turns: 100
 *   provider: anthropic
 *   model: claude-sonnet-4-20250514
 * }</pre>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "goose")
public class GooseProperties {

    /**
     * Whether Goose integration is enabled.
     */
    private boolean enabled = true;

    /**
     * Default timeout for Goose execution.
     */
    private Duration timeout = Duration.ofMinutes(5);

    /**
     * Maximum number of conversation turns (prevents runaway sessions).
     */
    private int maxTurns = 100;

    /**
     * Default LLM provider.
     */
    private String provider;

    /**
     * Default LLM model.
     */
    private String model;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}

