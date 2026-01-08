# Goose Buildpack Quick Start Guide

Get Goose running on Cloud Foundry in 5 minutes.

## Prerequisites

- Cloud Foundry CLI (`cf`) installed and logged in
- An LLM provider API key (Anthropic, OpenAI, Google, etc.)
- A Java application (Spring Boot recommended)

## Step 1: Add Configuration File

Create `.goose-config.yml` in your application root:

```yaml
goose:
  enabled: true
  version: "stable"
  provider: anthropic
  model: claude-sonnet-4-20250514
```

## Step 2: Update manifest.yml

```yaml
applications:
- name: my-goose-app
  memory: 1G
  buildpacks:
    - goose-buildpack
    - java_buildpack
  env:
    ANTHROPIC_API_KEY: sk-ant-your-api-key-here
    GOOSE_ENABLED: true
```

## Step 3: Add Java Dependency (Optional)

For Java/Spring Boot applications, add to `pom.xml`:

```xml
<dependency>
    <groupId>org.tanzu.goose</groupId>
    <artifactId>goose-cf-wrapper</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Step 4: Use in Your Code

### Spring Boot (Auto-configured)

```java
@RestController
public class GooseController {
    
    private final GooseExecutor gooseExecutor;
    
    public GooseController(GooseExecutor gooseExecutor) {
        this.gooseExecutor = gooseExecutor;
    }
    
    @PostMapping("/api/ask")
    public String ask(@RequestBody String prompt) {
        return gooseExecutor.execute(prompt);
    }
}
```

### Plain Java

```java
GooseExecutor executor = new GooseExecutorImpl();
String result = executor.execute("Analyze this code for bugs");
System.out.println(result);
```

## Step 5: Deploy

```bash
cf push
```

## Verify Installation

Check the staging logs for:

```
-----> Goose CLI Buildpack
       Validating environment...
       Anthropic API key format validated
       Installing Goose CLI...
       Downloading Goose CLI (stable release)...
       Extracting Goose binary...
       Goose CLI installed successfully
       Version: goose 1.19.0
```

## Test the API

```bash
curl -X POST https://my-goose-app.apps.example.com/api/ask \
  -H "Content-Type: application/json" \
  -d '"What is the capital of France?"'
```

## Using Different Providers

### OpenAI

```yaml
# .goose-config.yml
goose:
  provider: openai
  model: gpt-4
```

```yaml
# manifest.yml
env:
  OPENAI_API_KEY: sk-your-openai-key
```

### Google

```yaml
# .goose-config.yml
goose:
  provider: google
  model: gemini-pro
```

```yaml
# manifest.yml
env:
  GOOGLE_API_KEY: your-google-api-key
```

### Ollama (Local)

```yaml
# .goose-config.yml
goose:
  provider: ollama
  model: llama3
```

```yaml
# manifest.yml
env:
  OLLAMA_HOST: http://ollama.apps.internal:11434
```

## Troubleshooting

### "GOOSE_CLI_PATH not set"

The buildpack didn't run. Check that:
- `.goose-config.yml` exists in your app root
- `GOOSE_ENABLED=true` is set
- `goose-buildpack` is listed before `java_buildpack`

### "No LLM provider credentials found"

Set at least one provider API key:
- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY`
- `GOOGLE_API_KEY`

### Timeout errors

Increase the timeout:

```java
GooseOptions options = GooseOptions.builder()
    .timeout(Duration.ofMinutes(10))
    .build();

executor.execute(prompt, options);
```

Or set globally:

```yaml
env:
  GOOSE_TIMEOUT_MINUTES: 10
```

## Next Steps

- Configure [MCP servers](examples/sample-goose-config.yml) for extended capabilities
- Set up [streaming responses](java-wrapper/README.md#streaming) for real-time output
- Explore [async execution](java-wrapper/README.md#async-execution) for non-blocking operations

