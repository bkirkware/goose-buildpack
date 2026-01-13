package org.tanzu.goose.cf;

import java.util.List;

/**
 * Represents the Goose configuration parsed from ~/.config/goose/config.yaml.
 * <p>
 * This configuration includes skills (instruction sets) and MCP servers
 * that extend Goose's capabilities.
 * </p>
 *
 * @param provider the LLM provider (e.g., "openai", "anthropic")
 * @param model the model name
 * @param skills list of configured skills
 * @param mcpServers list of configured MCP servers
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public record GooseConfiguration(
    String provider,
    String model,
    List<SkillInfo> skills,
    List<McpServerInfo> mcpServers
) {
    /**
     * Returns an empty configuration with no skills or MCP servers.
     */
    public static GooseConfiguration empty() {
        return new GooseConfiguration(null, null, List.of(), List.of());
    }
}
