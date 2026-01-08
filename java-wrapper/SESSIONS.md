# Goose Session Management

This guide explains how to use the session management features in `goose-cf-wrapper` to maintain multi-turn conversations with Goose.

## Overview

Goose supports **named sessions** that persist conversation history across multiple messages. This enables multi-turn conversations where Goose remembers previous exchanges and maintains context.

Sessions are stored in Goose's SQLite database at `~/.local/share/goose/sessions/sessions.db`.

For more details, see the [official Goose Session Management documentation](https://block.github.io/goose/docs/guides/sessions/session-management/).

## Quick Start

```java
@Autowired
private GooseExecutor executor;

// Start a new session
String sessionName = "my-project-session";
String response1 = executor.executeInSession(sessionName, "Hello, I'm working on a React app", false);

// Continue the conversation (Goose remembers the context)
String response2 = executor.executeInSession(sessionName, "Add a login form component", true);

// Further follow-up (context is maintained)
String response3 = executor.executeInSession(sessionName, "Now add form validation", true);
```

## Session Methods

### executeInSession

Execute a prompt within a named session synchronously.

```java
// Start a new session
String result = executor.executeInSession("session-name", "prompt", false);

// Resume an existing session
String result = executor.executeInSession("session-name", "follow-up prompt", true);

// With custom options
GooseOptions options = GooseOptions.builder()
    .timeout(Duration.ofMinutes(10))
    .maxTurns(50)
    .build();
String result = executor.executeInSession("session-name", "prompt", true, options);
```

**Parameters:**
- `sessionName` - A unique name for the session (e.g., `"chat-abc123"`, `"react-migration"`)
- `prompt` - The message to send to Goose
- `resume` - `false` to start a new session, `true` to continue an existing one
- `options` - Optional execution configuration

### executeInSessionStreaming

Execute a prompt within a named session and receive output as a stream.

```java
// Stream output from a new session
try (Stream<String> lines = executor.executeInSessionStreaming("session-name", "prompt", false)) {
    lines.forEach(System.out::println);
}

// Stream output while resuming a session
try (Stream<String> lines = executor.executeInSessionStreaming("session-name", "prompt", true)) {
    lines.forEach(line -> {
        // Process each line as it arrives
        sendToClient(line);
    });
}
```

> **Important:** Always use try-with-resources or explicitly close the stream to prevent resource leaks.

## How It Works

Under the hood, the wrapper invokes Goose CLI with the `run` command for non-interactive execution:

**Starting a new session:**
```bash
goose run -n "my-session" -t "Hello" --max-turns 100
```

**Resuming an existing session:**
```bash
goose run -n "my-session" -r -t "Follow-up question" --max-turns 100
```

The `-r` (resume) flag tells Goose to load the previous conversation history before processing the new prompt.

> **Note:** We use `goose run` instead of `goose session` because:
> - `goose session` starts an interactive REPL that waits for user input
> - `goose run` executes a task non-interactively and exits when complete

## Session Lifecycle

### Creating Sessions

Sessions are created automatically when you call `executeInSession` with `resume=false`. Choose descriptive session names that help identify the conversation context:

```java
// Good session names
executor.executeInSession("user-123-chat", prompt, false);
executor.executeInSession("project-api-refactor", prompt, false);
executor.executeInSession("debug-session-2024-01-15", prompt, false);

// Avoid generic names that might conflict
// Bad: executor.executeInSession("session1", prompt, false);
```

### Resuming Sessions

To continue a conversation, use the same session name with `resume=true`:

```java
// First message starts the session
executor.executeInSession("my-session", "Analyze my codebase", false);

// Subsequent messages resume the session
executor.executeInSession("my-session", "Focus on the auth module", true);
executor.executeInSession("my-session", "What security issues did you find?", true);
```

### Session Persistence

Goose automatically persists sessions. You don't need to explicitly save or close sessions. The conversation history is stored in the SQLite database and can be resumed at any time.

## Example: Chat Application

Here's a complete example of a Spring controller using sessions:

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final GooseExecutor executor;
    private final Map<String, Boolean> sessionStarted = new ConcurrentHashMap<>();

    public ChatController(GooseExecutor executor) {
        this.executor = executor;
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<String> sendMessage(
            @PathVariable String sessionId,
            @RequestBody MessageRequest request) {
        
        // Determine if this is the first message in the session
        boolean isResume = sessionStarted.containsKey(sessionId);
        
        // Execute with session context
        String response = executor.executeInSession(
            sessionId, 
            request.getMessage(), 
            isResume,
            GooseOptions.builder()
                .timeout(Duration.ofMinutes(5))
                .build()
        );
        
        // Mark session as started for future messages
        sessionStarted.put(sessionId, true);
        
        return ResponseEntity.ok(response);
    }
}
```

## Example: Streaming with SSE

For real-time streaming responses:

```java
@PostMapping(value = "/sessions/{sessionId}/stream", 
             produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamMessage(
        @PathVariable String sessionId,
        @RequestBody MessageRequest request) {
    
    SseEmitter emitter = new SseEmitter(300_000L);
    boolean isResume = sessionStarted.containsKey(sessionId);
    
    executorService.execute(() -> {
        try (Stream<String> lines = executor.executeInSessionStreaming(
                sessionId, request.getMessage(), isResume)) {
            
            lines.forEach(line -> {
                try {
                    emitter.send(SseEmitter.event()
                        .name("message")
                        .data(line));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            
            sessionStarted.put(sessionId, true);
            emitter.complete();
            
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });
    
    return emitter;
}
```

## Configuration Options

Customize session execution with `GooseOptions`:

```java
GooseOptions options = GooseOptions.builder()
    .timeout(Duration.ofMinutes(10))      // Execution timeout
    .maxTurns(50)                          // Max conversation turns
    .provider("anthropic")                 // LLM provider (optional)
    .model("claude-sonnet-4-20250514")     // Model to use (optional)
    .addEnv("CUSTOM_VAR", "value")         // Additional env vars
    .build();

executor.executeInSession("session-name", "prompt", true, options);
```

### Provider and Model Resolution

The wrapper automatically resolves provider and model in this order:

1. **GooseOptions** - If `provider()` or `model()` is set in options, use that
2. **Environment Variables** - Fall back to `GOOSE_PROVIDER` and `GOOSE_MODEL` env vars
3. **Goose Config File** - The buildpack parses `.goose-config.yml` and exports these env vars

This means applications don't need to explicitly set provider/model if they're configured in `.goose-config.yml`:

```yaml
# .goose-config.yml (in src/main/resources/)
provider: openai
model: gpt-4o
```

The buildpack will parse this and set `GOOSE_PROVIDER=openai` and `GOOSE_MODEL=gpt-4o`, which the wrapper reads automatically.

## Best Practices

### 1. Use Unique Session Names

Generate unique session names to avoid conflicts between users or conversations:

```java
String sessionName = "user-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
```

### 2. Handle Session Expiry

Implement session timeout handling in your application:

```java
private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

public void sendMessage(String sessionId, String message) {
    Instant lastAccess = lastActivity.get(sessionId);
    boolean isResume = lastAccess != null && 
        Duration.between(lastAccess, Instant.now()).toMinutes() < 30;
    
    executor.executeInSession(sessionId, message, isResume);
    lastActivity.put(sessionId, Instant.now());
}
```

### 3. Start Fresh for New Tasks

While you can resume sessions indefinitely, starting a new session for distinct tasks helps maintain focus:

```java
// Start fresh for a new project
executor.executeInSession("project-v2-migration", "Help me migrate to React 19", false);

// Don't reuse old sessions for unrelated work
// Bad: executor.executeInSession("old-debugging-session", "Help me with React 19", true);
```

### 4. Handle Errors Gracefully

```java
try {
    String response = executor.executeInSession(sessionId, message, true);
    return response;
} catch (GooseExecutionException e) {
    if (e.getMessage().contains("session not found")) {
        // Session may have been cleaned up - start fresh
        return executor.executeInSession(sessionId, message, false);
    }
    throw e;
}
```

## Comparison with Single-Shot Execution

| Feature | Single-Shot (`execute`) | Session (`executeInSession`) |
|---------|------------------------|------------------------------|
| Context | No memory | Remembers conversation |
| Use case | One-off tasks | Multi-turn conversations |
| CLI command | `goose run -t "prompt"` | `goose run -n X [-r] -t "prompt"` |
| Storage | None | SQLite database |

## Related Resources

- [Goose Session Management Guide](https://block.github.io/goose/docs/guides/sessions/session-management/)
- [Goose CLI Commands](https://block.github.io/goose/docs/guides/goose-cli-commands/)
- [GooseExecutor Interface](src/main/java/org/tanzu/goose/cf/GooseExecutor.java)

