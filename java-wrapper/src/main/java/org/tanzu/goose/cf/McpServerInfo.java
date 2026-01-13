package org.tanzu.goose.cf;

import java.util.List;
import java.util.Map;

/**
 * Information about a configured MCP (Model Context Protocol) server.
 * <p>
 * MCP servers extend Goose's capabilities by providing additional tools
 * and resources. They can run as local processes (stdio) or remote
 * HTTP services (streamable_http).
 * </p>
 *
 * @param name the server name (unique identifier)
 * @param type the transport type: "stdio" or "streamable_http"
 * @param url the server URL (for streamable_http servers)
 * @param command the command to run (for stdio servers)
 * @param args command arguments (for stdio servers)
 * @param env environment variables for the server
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public record McpServerInfo(
    String name,
    String type,
    String url,
    String command,
    List<String> args,
    Map<String, String> env
) {
    /**
     * Creates an HTTP-based MCP server.
     */
    public static McpServerInfo http(String name, String url) {
        return new McpServerInfo(name, "streamable_http", url, null, null, null);
    }

    /**
     * Creates a stdio-based MCP server.
     */
    public static McpServerInfo stdio(String name, String command, List<String> args) {
        return new McpServerInfo(name, "stdio", null, command, args, null);
    }
}
