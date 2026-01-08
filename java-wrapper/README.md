# Goose Cloud Foundry Java Wrapper

A Java wrapper library for invoking the Goose CLI from Java applications running on Cloud Foundry.

## Overview

This library provides a clean Java API for interacting with the [Goose](https://github.com/block/goose) AI coding agent CLI. It's designed to work seamlessly with the Goose Cloud Foundry Buildpack.

## Key Features

- **Simple API** - Execute Goose commands with a single method call
- **Streaming Support** - Real-time streaming of Goose output
- **Async Execution** - Non-blocking async API with CompletableFuture
- **Spring Boot Integration** - Auto-configuration for Spring Boot applications
- **Multi-Provider** - Supports Anthropic, OpenAI, Google, Databricks, Ollama, and more

## Installation

### Maven

```xml
<dependency>
    <groupId>org.tanzu.goose</groupId>
    <artifactId>goose-cf-wrapper</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'org.tanzu.goose:goose-cf-wrapper:1.0.0'
```

## Usage

### Basic Usage

```java
// Create executor (uses environment variables)
GooseExecutor executor = new GooseExecutorImpl();

// Execute a prompt
String result = executor.execute("Analyze this code for bugs");
System.out.println(result);
```

### With Options

```java
GooseOptions options = GooseOptions.builder()
    .timeout(Duration.ofMinutes(10))
    .maxTurns(50)
    .build();

String result = executor.execute("Generate unit tests", options);
```

### Async Execution

```java
CompletableFuture<String> future = executor.executeAsync("Refactor this code");

future.thenAccept(result -> {
    System.out.println("Goose response: " + result);
});
```

### Streaming

```java
// IMPORTANT: Always use try-with-resources for streaming
try (Stream<String> lines = executor.executeStreaming("Explain this algorithm")) {
    lines.forEach(System.out::println);
}
```

### Spring Boot Integration

With Spring Boot, the `GooseExecutor` is automatically configured as a bean:

```java
@RestController
public class MyController {
    
    private final GooseExecutor gooseExecutor;
    
    public MyController(GooseExecutor gooseExecutor) {
        this.gooseExecutor = gooseExecutor;
    }
    
    @PostMapping("/analyze")
    public String analyze(@RequestBody String prompt) {
        return gooseExecutor.execute(prompt);
    }
}
```

## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `GOOSE_CLI_PATH` | Path to Goose CLI binary | Yes |
| `ANTHROPIC_API_KEY` | Anthropic API key | One provider required |
| `OPENAI_API_KEY` | OpenAI API key | One provider required |
| `GOOGLE_API_KEY` | Google API key | One provider required |
| `DATABRICKS_HOST` | Databricks host | With DATABRICKS_TOKEN |
| `DATABRICKS_TOKEN` | Databricks token | With DATABRICKS_HOST |
| `OLLAMA_HOST` | Ollama server URL | For local inference |
| `GOOSE_TIMEOUT_MINUTES` | Default timeout | No (default: 5) |
| `GOOSE_MAX_TURNS` | Max conversation turns | No (default: 100) |

### Spring Boot Properties

```yaml
goose:
  enabled: true
  timeout: 5m
  max-turns: 100
  provider: anthropic
  model: claude-sonnet-4-20250514
  controller:
    enabled: true  # Enable REST endpoints
```

## REST API (Spring Boot)

When using Spring Boot with WebFlux, the following endpoints are available:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/goose/execute` | POST | Execute prompt, return complete response |
| `/api/goose/stream` | GET | Execute prompt with SSE streaming |
| `/api/goose/health` | GET | Check if Goose CLI is available |
| `/api/goose/version` | GET | Get Goose CLI version |

### Example Request

```bash
curl -X POST http://localhost:8080/api/goose/execute \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is the capital of France?"}'
```

## Comparison with Claude Code

| Aspect | Claude Code | Goose |
|--------|-------------|-------|
| Runtime | Node.js required | Native binary (no runtime) |
| CLI Path | `CLAUDE_CLI_PATH` | `GOOSE_CLI_PATH` |
| Invocation | `claude -p "prompt"` | `goose session --text "prompt"` |
| Providers | Anthropic only | Multiple (Anthropic, OpenAI, etc.) |
| Binary Size | ~100MB (with Node) | ~30MB |

## Requirements

- Java 21+
- Goose CLI (installed via buildpack)
- At least one LLM provider API key

## License

MIT License

