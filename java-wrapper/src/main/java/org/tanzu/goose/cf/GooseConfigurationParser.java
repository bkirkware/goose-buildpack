package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Parses Goose configuration from YAML files.
 * <p>
 * Searches for configuration in the following order:
 * </p>
 * <ol>
 *   <li>{@code ~/.config/goose/config.yaml} (Goose native format)</li>
 *   <li>{@code /home/vcap/app/.config/goose/config.yaml} (CF buildpack location)</li>
 * </ol>
 *
 * @author Goose Buildpack Team
 * @since 1.0.0
 */
public class GooseConfigurationParser {

    private static final Logger logger = LoggerFactory.getLogger(GooseConfigurationParser.class);

    /**
     * Parse the Goose configuration from config files.
     *
     * @return the parsed configuration, or an empty configuration if not found
     */
    public GooseConfiguration parse() {
        Path configFile = findConfigFile();
        
        if (configFile == null) {
            return GooseConfiguration.empty();
        }
        
        try {
            String content = Files.readString(configFile);
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(content);
            
            if (config == null) {
                return GooseConfiguration.empty();
            }
            
            String provider = getStringValue(config, "provider");
            String model = getStringValue(config, "model");
            
            // Parse skills from config and from skills directory
            List<SkillInfo> skills = parseSkills(config);
            List<SkillInfo> installedSkills = parseInstalledSkills(configFile.getParent());
            if (!installedSkills.isEmpty()) {
                skills = new ArrayList<>(skills);
                skills.addAll(installedSkills);
            }
            
            // Parse MCP servers - check both 'mcpServers' and 'extensions' formats
            List<McpServerInfo> mcpServers = parseMcpServers(config);
            if (mcpServers.isEmpty()) {
                // Try parsing from extensions (buildpack-transformed format)
                mcpServers = parseMcpServersFromExtensions(config);
            }
            
            logger.info("Parsed Goose configuration: provider={}, model={}, skills={}, mcpServers={}", 
                provider, model, skills.size(), mcpServers.size());
            
            return new GooseConfiguration(provider, model, skills, mcpServers);
            
        } catch (IOException e) {
            logger.error("Failed to read Goose configuration from: {}", configFile, e);
            return GooseConfiguration.empty();
        } catch (Exception e) {
            logger.error("Failed to parse Goose configuration", e);
            return GooseConfiguration.empty();
        }
    }

    /**
     * Find the configuration file from known locations.
     */
    private Path findConfigFile() {
        String home = System.getenv("HOME");
        
        List<Path> configPaths = new ArrayList<>();
        if (home != null && !home.isEmpty()) {
            configPaths.add(Paths.get(home, ".config", "goose", "config.yaml"));
        }
        // CF buildpack copies config to /home/vcap/app/.config/goose/
        configPaths.add(Paths.get("/home/vcap/app/.config/goose/config.yaml"));
        
        for (Path path : configPaths) {
            if (Files.exists(path)) {
                logger.info("Found Goose config file at: {}", path);
                return path;
            }
        }
        
        logger.info("No Goose config file found in any of: {}", configPaths);
        return null;
    }
    
    /**
     * Parse installed skills from the skills directory.
     * Skills are installed to ~/.config/goose/skills/{skill-name}/SKILL.md
     */
    private List<SkillInfo> parseInstalledSkills(Path configDir) {
        Path skillsDir = configDir.resolve("skills");
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return List.of();
        }
        
        List<SkillInfo> skills = new ArrayList<>();
        try {
            Files.list(skillsDir)
                .filter(Files::isDirectory)
                .forEach(skillDir -> {
                    String name = skillDir.getFileName().toString();
                    Path skillFile = skillDir.resolve("SKILL.md");
                    String description = null;
                    String source = "file";
                    
                    if (Files.exists(skillFile)) {
                        try {
                            String content = Files.readString(skillFile);
                            description = parseSkillDescription(content);
                        } catch (IOException e) {
                            logger.debug("Could not read skill file: {}", skillFile);
                        }
                    }
                    
                    skills.add(new SkillInfo(name, description, source, skillDir.toString(), null, null));
                });
        } catch (IOException e) {
            logger.warn("Error listing skills directory: {}", skillsDir, e);
        }
        
        return skills;
    }
    
    /**
     * Parse description from SKILL.md YAML frontmatter.
     */
    private String parseSkillDescription(String content) {
        if (!content.startsWith("---")) {
            return null;
        }
        
        int endIndex = content.indexOf("---", 3);
        if (endIndex < 0) {
            return null;
        }
        
        String frontmatter = content.substring(3, endIndex).trim();
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> meta = yaml.load(frontmatter);
            if (meta != null) {
                return getStringValue(meta, "description");
            }
        } catch (Exception e) {
            logger.debug("Could not parse SKILL.md frontmatter");
        }
        
        return null;
    }
    
    /**
     * Parse MCP servers from the 'extensions' format (buildpack-transformed config).
     */
    @SuppressWarnings("unchecked")
    private List<McpServerInfo> parseMcpServersFromExtensions(Map<String, Object> config) {
        Object extensionsObj = config.get("extensions");
        if (!(extensionsObj instanceof Map<?, ?> extensionsMap)) {
            return List.of();
        }
        
        List<McpServerInfo> servers = new ArrayList<>();
        for (Map.Entry<?, ?> entry : extensionsMap.entrySet()) {
            String name = entry.getKey().toString();
            if (!(entry.getValue() instanceof Map<?, ?> extMap)) {
                continue;
            }
            
            Map<String, Object> ext = (Map<String, Object>) extMap;
            String type = getStringValue(ext, "type");
            
            // Skip builtin extensions - only include MCP servers
            if ("builtin".equals(type)) {
                continue;
            }
            
            // streamable_http or stdio are MCP server types
            if ("streamable_http".equals(type) || "stdio".equals(type)) {
                String url = getStringValue(ext, "uri");
                if (url == null) {
                    url = getStringValue(ext, "url");
                }
                String command = getStringValue(ext, "command");
                
                List<String> args = parseStringList(ext.get("args"));
                Map<String, String> env = parseStringMap(ext.get("env"));
                
                servers.add(new McpServerInfo(name, type, url, command, args, env));
            }
        }
        
        return servers;
    }
    
    /**
     * Parse skills from the configuration.
     */
    @SuppressWarnings("unchecked")
    private List<SkillInfo> parseSkills(Map<String, Object> config) {
        Object skillsObj = config.get("skills");
        if (!(skillsObj instanceof List<?> skillsList)) {
            return List.of();
        }
        
        List<SkillInfo> skills = new ArrayList<>();
        for (Object item : skillsList) {
            if (item instanceof Map<?, ?> skillMap) {
                Map<String, Object> skill = (Map<String, Object>) skillMap;
                String name = getStringValue(skill, "name");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                
                String description = getStringValue(skill, "description");
                String path = getStringValue(skill, "path");
                String source = getStringValue(skill, "source");
                String branch = getStringValue(skill, "branch");
                
                // Determine source type
                String sourceType;
                String repository = null;
                if (source != null && !source.isEmpty()) {
                    sourceType = "git";
                    repository = source;
                } else if (path != null && !path.isEmpty()) {
                    sourceType = "file";
                } else {
                    sourceType = "inline";
                }
                
                skills.add(new SkillInfo(name, description, sourceType, path, repository, branch));
            }
        }
        
        return skills;
    }
    
    /**
     * Parse MCP servers from the configuration.
     */
    @SuppressWarnings("unchecked")
    private List<McpServerInfo> parseMcpServers(Map<String, Object> config) {
        Object serversObj = config.get("mcpServers");
        if (!(serversObj instanceof List<?> serversList)) {
            return List.of();
        }
        
        List<McpServerInfo> servers = new ArrayList<>();
        for (Object item : serversList) {
            if (item instanceof Map<?, ?> serverMap) {
                Map<String, Object> server = (Map<String, Object>) serverMap;
                String name = getStringValue(server, "name");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                
                String type = getStringValue(server, "type");
                String url = getStringValue(server, "url");
                String command = getStringValue(server, "command");
                
                List<String> args = parseStringList(server.get("args"));
                Map<String, String> env = parseStringMap(server.get("env"));
                
                servers.add(new McpServerInfo(name, type, url, command, args, env));
            }
        }
        
        return servers;
    }
    
    /**
     * Safely get a string value from a map.
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
    
    /**
     * Parse a list of strings from an object.
     */
    private List<String> parseStringList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }
    
    /**
     * Parse a map of strings from an object.
     */
    private Map<String, String> parseStringMap(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }
}
