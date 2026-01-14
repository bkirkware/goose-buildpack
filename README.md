# Goose Cloud Foundry Buildpack

A Cloud Foundry supply buildpack that bundles the [Goose](https://github.com/block/goose) AI coding agent CLI into containers, enabling Java applications to invoke Goose programmatically.

## Overview

This buildpack installs the Goose CLI (a native Rust binary) into Cloud Foundry containers, allowing applications to leverage Goose for AI-assisted coding tasks. Unlike other AI agent buildpacks, **no runtime (Node.js, Python) is required** - Goose is a single self-contained binary.

## Features

- **Native Binary** - Goose CLI is a ~30MB Rust binary, no runtime dependencies
- **Multi-Provider Support** - Works with Anthropic, OpenAI, Google, Databricks, Ollama, and more
- **Java Wrapper Library** - Clean Java API for invoking Goose from Spring Boot applications
- **MCP Support** - Configure Model Context Protocol servers to extend capabilities
- **Skills Support** - Define reusable instruction sets for common workflows
- **Spring Boot Ready** - Auto-configuration for seamless Spring Boot integration

## Quick Start

### 1. Create Configuration File

Add `.goose-config.yml` to your application root:

```yaml
goose:
  enabled: true
  version: "stable"
  provider: anthropic
  model: claude-sonnet-4-20250514
```

### 2. Update Manifest

Configure your `manifest.yml`:

```yaml
applications:
- name: my-app
  buildpacks:
    - goose-buildpack
    - java_buildpack
  env:
    ANTHROPIC_API_KEY: sk-ant-xxxxx
    GOOSE_ENABLED: true
```

### 3. Add Java Wrapper (Optional)

For Java applications, add the wrapper library:

```xml
<dependency>
    <groupId>org.tanzu.goose</groupId>
    <artifactId>goose-cf-wrapper</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 4. Use in Code

```java
@Autowired
private GooseExecutor gooseExecutor;

public void processWithGoose() {
    String result = gooseExecutor.execute("Analyze this code for security issues");
    System.out.println(result);
}
```

## Detection

The buildpack detects and activates when:

1. `.goose-config.yml` or `.goose-config.yaml` exists in the app root
2. A JAR file contains `.goose-config.yml` (Spring Boot apps)
3. `GOOSE_ENABLED=true` environment variable is set
4. `manifest.yml` contains `goose-enabled: true`

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `GOOSE_ENABLED` | Enable buildpack detection | No |
| `GOOSE_VERSION` | Goose version (default: stable) | No |
| `ANTHROPIC_API_KEY` | Anthropic API key | One provider required |
| `OPENAI_API_KEY` | OpenAI API key | One provider required |
| `GOOGLE_API_KEY` | Google API key | One provider required |
| `DATABRICKS_HOST` | Databricks workspace URL | With DATABRICKS_TOKEN |
| `DATABRICKS_TOKEN` | Databricks access token | With DATABRICKS_HOST |
| `OLLAMA_HOST` | Ollama server URL | For local inference |
| `GOOSE_PROVIDER` | Default LLM provider | No |
| `GOOSE_MODEL` | Default LLM model | No |
| `GOOSE_LOG_LEVEL` | Log level (debug/info/warn/error) | No |
| `GOOSE_TIMEOUT_MINUTES` | Execution timeout | No (default: 5) |
| `GOOSE_MAX_TURNS` | Max conversation turns | No (default: 100) |

### Configuration File (.goose-config.yml)

```yaml
goose:
  enabled: true
  version: "stable"              # or "v1.19.0"
  
  # LLM Provider
  provider: anthropic            # anthropic, openai, google, databricks, ollama
  model: claude-sonnet-4-20250514
  
  # Settings
  logLevel: info                 # debug, info, warn, error
  
  # MCP Servers (optional)
  mcpServers:
    - name: github
      type: stdio
      command: npx
      args:
        - "-y"
        - "@modelcontextprotocol/server-github"
      env:
        GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  
  # Skills (optional) - reusable instruction sets
  skills:
    - name: code-review
      description: Code review checklist
      content: |
        # Code Review Checklist
        - [ ] Code does what the PR claims
        - [ ] Edge cases handled
        - [ ] Follows project style guide
```

### MCP Server Auto-Configuration via Service Binding

The buildpack automatically discovers and configures MCP servers from Cloud Foundry service bindings. When a service instance is bound to your application with the `mcp` tag, the buildpack will:

1. Extract the MCP server URI from the service credentials
2. Automatically add it to Goose's configuration as a `streamable_http` extension
3. Make it available to Goose without manual configuration

**Requirements:**
- The service binding must be tagged with `mcp` (case-sensitive)
- The service credentials must include a `uri` or `url` field
- Optional: `headers` field in credentials for authentication

When creating the service instance, ensure it's tagged with `mcp`.

Example: MCP server published on the Cloud Foundry marketplace.

```bash
cf create-service my-service my-plan my-mcp-server -t mcp
```

Example: MCP server created with a user-provided service.

```bash
cf create-user-provided-service my-mcp-server \
  -p '{"uri":"https://my-mcp-server.apps.example.com","headers":{"Authorization":"Bearer token"}}' \
  -t mcp
```

The buildpack will automatically detect and configure the MCP server at runtime. Service bindings take precedence over manually configured MCP servers in `.goose-config.yml` - if a service binding has the same name as a config file server, the service binding will be used.

## Skills

Skills are reusable sets of instructions that teach Goose how to perform specific tasks. They follow the [Agent Skills](https://block.github.io/goose/docs/guides/context-engineering/using-skills) format compatible with Claude Desktop and other agents.

### Skill Types

The buildpack supports three types of skills:

#### 1. Inline Skills

Embed skill content directly in your `.goose-config.yml`:

```yaml
skills:
  - name: production-deploy
    description: Safe deployment procedure for production
    content: |
      # Production Deployment
      ## Pre-deployment
      1. Ensure all tests pass
      2. Get approval from reviewers
      3. Notify #deployments channel
      ## Deploy
      1. Create release branch from main
      2. Run ./gradlew build
      3. Deploy to staging, verify, then production
```

#### 2. File-Based Skills

Reference skills from directories in your application:

```yaml
skills:
  - name: deployment
    path: .goose/skills/deployment
```

The skill directory must contain a `SKILL.md` file with YAML frontmatter:

```markdown
---
name: deployment
description: Deployment workflow for this project
---

# Deployment Steps
1. Build the application
2. Run integration tests
...
```

#### 3. Git-Based Skills

Clone skills from a Git repository during staging:

```yaml
skills:
  - name: company-standards
    source: https://github.com/org/goose-skills.git
    branch: main
    path: skills/company-standards
```

> **Note:** Git-based skills require network access during Cloud Foundry staging.

### Skill Locations

Skills are installed to `.goose/skills/` in the application directory and copied to `~/.config/goose/skills/` at runtime. Goose automatically discovers and uses skills based on your requests.

## Java Wrapper Library

The buildpack includes a Java wrapper library for easy integration:

### Synchronous Execution

```java
GooseExecutor executor = new GooseExecutorImpl();
String result = executor.execute("What is this code doing?");
```

### With Options

```java
GooseOptions options = GooseOptions.builder()
    .timeout(Duration.ofMinutes(10))
    .maxTurns(50)
    .build();

String result = executor.execute("Refactor this function", options);
```

### Streaming

```java
try (Stream<String> lines = executor.executeStreaming("Explain this algorithm")) {
    lines.forEach(System.out::println);
}
```

### Spring Boot Integration

The wrapper auto-configures with Spring Boot:

```yaml
# application.yml
goose:
  enabled: true
  timeout: 5m
  max-turns: 100
```

```java
@RestController
public class AIController {
    
    private final GooseExecutor gooseExecutor;
    
    @PostMapping("/api/ai/analyze")
    public String analyze(@RequestBody String prompt) {
        return gooseExecutor.execute(prompt);
    }
}
```

## Comparison with Claude Code

| Aspect | Claude Code Buildpack | Goose Buildpack |
|--------|----------------------|-----------------|
| Runtime | Node.js required | No runtime (native binary) |
| Binary Source | npm install | GitHub releases |
| Config File | `.claude-code-config.yml` | `.goose-config.yml` |
| Authentication | `ANTHROPIC_API_KEY` only | Multiple providers |
| Size | ~100MB (Node + npm) | ~30MB (single binary) |
| CLI Invocation | `claude -p "prompt"` | `goose session --text "prompt"` |

## Directory Structure

```
goose-buildpack/
├── bin/
│   ├── detect           # Buildpack detection script
│   └── supply           # Buildpack supply script
├── lib/
│   ├── environment.sh   # Environment setup
│   ├── goose_configurator.sh  # MCP/config management
│   ├── installer.sh     # Goose binary installation
│   └── validator.sh     # Validation utilities
├── java-wrapper/        # Java wrapper library
│   ├── pom.xml
│   └── src/main/java/org/tanzu/goose/cf/
│       ├── GooseExecutor.java
│       ├── GooseExecutorImpl.java
│       ├── GooseExecutionException.java
│       ├── GooseOptions.java
│       └── spring/      # Spring Boot integration
├── examples/
│   ├── sample-goose-config.yml
│   └── sample-manifest.yml
├── resources/
│   └── default-config.yml
├── buildpack.yml
└── README.md
```

## Supported Providers

| Provider | Environment Variables | Notes |
|----------|----------------------|-------|
| Anthropic | `ANTHROPIC_API_KEY` | Claude models |
| OpenAI | `OPENAI_API_KEY` | GPT models |
| Google | `GOOGLE_API_KEY` | Gemini models |
| Databricks | `DATABRICKS_HOST`, `DATABRICKS_TOKEN` | Enterprise |
| Ollama | `OLLAMA_HOST` | Local inference |
| Azure OpenAI | `AZURE_OPENAI_*` | Azure-hosted |
| AWS Bedrock | `AWS_*` | AWS-hosted |

## Requirements

- Cloud Foundry with cflinuxfs4 stack (Ubuntu 22.04)
- At least one LLM provider API key
- For Java wrapper: Java 21+

## License

MIT License - see [LICENSE](LICENSE)

## References

- [Goose GitHub Repository](https://github.com/block/goose)
- [Goose Documentation](https://block.github.io/goose/)
- [Goose Skills Guide](https://block.github.io/goose/docs/guides/context-engineering/using-skills)
- [Cloud Foundry Buildpack Documentation](https://docs.cloudfoundry.org/buildpacks/)
- [MCP (Model Context Protocol)](https://modelcontextprotocol.io/)

