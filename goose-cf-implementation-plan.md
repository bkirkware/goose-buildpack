# Goose on Cloud Foundry: Implementation Plan

## Executive Summary

This document outlines the implementation strategy for two projects that will enable Goose, Block's open-source AI coding agent, to run on Cloud Foundry:

1. **Project 1: Goose CLI Buildpack** - A supply buildpack that bundles the Goose CLI into Cloud Foundry containers, enabling Java applications to invoke Goose programmatically
2. **Project 2: Goose Web UI** - Investigation and implementation plan for deploying a web-based Goose interface to Cloud Foundry

---

## Architecture Overview

### Goose Project Architecture

Based on analysis of the [Goose repository](https://github.com/block/goose), the project consists of:

| Component | Technology | Description |
|-----------|------------|-------------|
| **goose-cli** | Rust (native binary) | Command-line interface for Goose |
| **goose-server** | Rust (Axum framework) | HTTP API server (called `goosed`) |
| **goose-mcp** | Rust | MCP (Model Context Protocol) integration |
| **goose Desktop** | Electron + React | Desktop application that embeds `goosed` |

**Key Difference from Claude Code**: Goose CLI is a **native Rust binary** (not Node.js), which simplifies the buildpack since no runtime is required. The binary is self-contained and available for `x86_64-unknown-linux-gnu`.

### Release Assets (v1.19.0)

| Asset | Platform | Use Case |
|-------|----------|----------|
| `goose-x86_64-unknown-linux-gnu.tar.bz2` | Linux x86_64 | **CF Container (use this)** |
| `goose-aarch64-unknown-linux-gnu.tar.bz2` | Linux ARM64 | ARM-based CF deployments |
| `goose_1.19.0_amd64.deb` | Debian/Ubuntu | Alternative installation |
| `Goose.zip` / `Goose_intel_mac.zip` | macOS | Desktop app (not for CF) |

---

## Project 1: Goose Buildpack for Cloud Foundry

### Objective

Create a supply buildpack that installs the Goose CLI into Cloud Foundry containers, allowing Java applications to invoke Goose for AI-assisted coding tasks.

### Architecture

```
┌─────────────────────────────────────────┐
│   Java Application                      │
│   - ProcessBuilder to invoke goose      │
│   - Stream handling for real-time output│
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│   Goose CLI                             │
│   - Native Rust binary                  │
│   - No runtime required                 │
│   - Installed at /home/vcap/deps/X/bin  │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│   LLM Provider (via API)                │
│   - OpenAI, Anthropic, Google, etc.     │
│   - Configured via environment vars     │
└─────────────────────────────────────────┘
```

### Implementation Phases

#### Phase 1: Core Buildpack (Week 1-2)

**Deliverables:**
- `bin/detect` - Detection script
- `bin/supply` - Installation script
- `lib/installer.sh` - Goose binary installation
- `lib/environment.sh` - Environment setup
- Basic documentation

**Detection Logic:**
```bash
#!/usr/bin/env bash
# bin/detect

BUILD_DIR=$1

# Detect if Goose should be enabled
if [ -f "${BUILD_DIR}/.goose-config.yml" ]; then
    echo "goose"
    exit 0
fi

if [ -f "${BUILD_DIR}/.goose-config.yaml" ]; then
    echo "goose"
    exit 0
fi

# Check environment variable (set in manifest.yml)
if [ "${GOOSE_ENABLED:-false}" = "true" ]; then
    echo "goose"
    exit 0
fi

exit 1
```

**Installation Logic (lib/installer.sh):**
```bash
install_goose() {
    local DEPS_DIR=$1
    local CACHE_DIR=$2
    local GOOSE_VERSION="${GOOSE_VERSION:-latest}"
    
    local GOOSE_URL
    if [ "$GOOSE_VERSION" = "latest" ]; then
        GOOSE_URL="https://github.com/block/goose/releases/download/stable/goose-x86_64-unknown-linux-gnu.tar.bz2"
    else
        GOOSE_URL="https://github.com/block/goose/releases/download/${GOOSE_VERSION}/goose-x86_64-unknown-linux-gnu.tar.bz2"
    fi
    
    local CACHE_FILE="${CACHE_DIR}/goose-${GOOSE_VERSION}.tar.bz2"
    
    # Download if not cached
    if [ ! -f "$CACHE_FILE" ]; then
        echo "       Downloading Goose CLI..."
        curl -fsSL "$GOOSE_URL" -o "$CACHE_FILE"
    else
        echo "       Using cached Goose CLI..."
    fi
    
    # Extract binary
    echo "       Extracting Goose binary..."
    mkdir -p "${DEPS_DIR}/bin"
    tar -xjf "$CACHE_FILE" -C "${DEPS_DIR}/bin"
    chmod +x "${DEPS_DIR}/bin/goose"
    
    # Verify installation
    "${DEPS_DIR}/bin/goose" --version
}
```

**Environment Setup (lib/environment.sh):**
```bash
setup_environment() {
    local DEPS_DIR=$1
    local BUILD_DIR=$2
    local INDEX=$3
    
    mkdir -p "${BUILD_DIR}/.profile.d"
    
    cat > "${BUILD_DIR}/.profile.d/goose-env.sh" << 'EOF'
#!/usr/bin/env bash

# Set Goose CLI path
export GOOSE_CLI_PATH="/home/vcap/deps/${INDEX}/bin/goose"
export PATH="/home/vcap/deps/${INDEX}/bin:$PATH"

# Goose configuration directory
export GOOSE_CONFIG_DIR="${HOME}/.config/goose"
mkdir -p "$GOOSE_CONFIG_DIR"

# Provider configuration (if not already set)
# Supported: openai, anthropic, google, databricks, ollama, etc.
if [ -n "$GOOSE_PROVIDER" ]; then
    export GOOSE_PROVIDER__TYPE="${GOOSE_PROVIDER}"
fi

if [ -n "$GOOSE_MODEL" ]; then
    export GOOSE_PROVIDER__MODEL="${GOOSE_MODEL}"
fi
EOF
    
    # Replace INDEX placeholder
    sed -i "s/\${INDEX}/${INDEX}/g" "${BUILD_DIR}/.profile.d/goose-env.sh"
}
```

#### Phase 2: Configuration Management (Week 2-3)

**Configuration File Support (.goose-config.yml):**
```yaml
goose:
  enabled: true
  version: "v1.19.0"  # or "latest"
  
  # LLM Provider configuration
  provider:
    type: anthropic  # openai, anthropic, google, databricks, ollama
    model: claude-sonnet-4-20250514
    # API key should be set via environment variable
  
  # Extension configuration
  extensions:
    developer:
      enabled: true
    
  # MCP servers (similar to Claude Code)
  mcpServers:
    - name: github
      type: stdio
      command: npx
      args:
        - "-y"
        - "@modelcontextprotocol/server-github"
      env:
        GITHUB_PERSONAL_ACCESS_TOKEN: "${GITHUB_TOKEN}"
```

**Configuration Parser (lib/config_parser.py):**
```python
#!/usr/bin/env python3
import yaml
import json
import os
import sys

def parse_config(config_path):
    """Parse .goose-config.yml and generate Goose configuration."""
    with open(config_path, 'r') as f:
        config = yaml.safe_load(f)
    
    goose_config = config.get('goose', {})
    
    # Generate profiles.yaml for Goose
    profiles = {
        'default': {
            'provider': goose_config.get('provider', {}).get('type', 'anthropic'),
            'model': goose_config.get('provider', {}).get('model', 'claude-sonnet-4-20250514'),
        }
    }
    
    # Generate MCP server configuration
    mcp_servers = {}
    for server in goose_config.get('mcpServers', []):
        name = server['name']
        mcp_servers[name] = {
            'type': server.get('type', 'stdio'),
            'command': server.get('command'),
            'args': server.get('args', []),
            'env': substitute_env_vars(server.get('env', {}))
        }
    
    return profiles, mcp_servers

def substitute_env_vars(env_dict):
    """Replace ${VAR} patterns with actual environment variables."""
    result = {}
    for key, value in env_dict.items():
        if isinstance(value, str) and value.startswith('${') and value.endswith('}'):
            env_var = value[2:-1]
            result[key] = os.environ.get(env_var, '')
        else:
            result[key] = value
    return result

if __name__ == '__main__':
    config_path = sys.argv[1]
    output_dir = sys.argv[2]
    
    profiles, mcp_servers = parse_config(config_path)
    
    # Write profiles.yaml
    with open(os.path.join(output_dir, 'profiles.yaml'), 'w') as f:
        yaml.dump({'profiles': profiles}, f)
    
    # Write MCP configuration if present
    if mcp_servers:
        with open(os.path.join(output_dir, 'mcp_servers.json'), 'w') as f:
            json.dump(mcp_servers, f, indent=2)
```

#### Phase 3: Java Wrapper Library (Week 3-4)

**Maven Coordinates:**
```xml
<dependency>
    <groupId>org.tanzu.goose</groupId>
    <artifactId>goose-cf-wrapper</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Java Wrapper Interface:**
```java
package org.tanzu.goose.cf;

public interface GooseExecutor {
    /**
     * Execute a Goose session with the given prompt.
     * @param prompt The task or question for Goose
     * @return The response from Goose
     */
    String execute(String prompt) throws GooseException;
    
    /**
     * Execute with streaming output.
     * @param prompt The task or question
     * @param outputHandler Handler for streaming output
     */
    void executeStreaming(String prompt, Consumer<String> outputHandler) throws GooseException;
    
    /**
     * Execute with a specific working directory.
     */
    String execute(String prompt, Path workingDirectory) throws GooseException;
}
```

**Implementation:**
```java
package org.tanzu.goose.cf;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class GooseExecutorImpl implements GooseExecutor {
    
    private final String goosePath;
    private final long timeoutMinutes;
    
    public GooseExecutorImpl() {
        this.goosePath = System.getenv().getOrDefault("GOOSE_CLI_PATH", 
            "/home/vcap/deps/0/bin/goose");
        this.timeoutMinutes = Long.parseLong(
            System.getenv().getOrDefault("GOOSE_TIMEOUT_MINUTES", "5"));
    }
    
    @Override
    public String execute(String prompt) throws GooseException {
        return execute(prompt, null);
    }
    
    @Override
    public String execute(String prompt, Path workingDirectory) throws GooseException {
        StringBuilder output = new StringBuilder();
        executeStreaming(prompt, workingDirectory, line -> output.append(line).append("\n"));
        return output.toString();
    }
    
    @Override
    public void executeStreaming(String prompt, Consumer<String> outputHandler) 
            throws GooseException {
        executeStreaming(prompt, null, outputHandler);
    }
    
    private void executeStreaming(String prompt, Path workingDirectory, 
            Consumer<String> outputHandler) throws GooseException {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                goosePath,
                "session",
                "--text", prompt,
                "--max-turns", "100"  // Prevent runaway sessions
            );
            
            // Set working directory if specified
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }
            
            // Configure environment
            Map<String, String> env = pb.environment();
            
            // Pass through API keys
            copyEnvIfPresent(env, "ANTHROPIC_API_KEY");
            copyEnvIfPresent(env, "OPENAI_API_KEY");
            copyEnvIfPresent(env, "GOOGLE_API_KEY");
            copyEnvIfPresent(env, "DATABRICKS_HOST");
            copyEnvIfPresent(env, "DATABRICKS_TOKEN");
            
            env.put("HOME", System.getenv("HOME"));
            
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            process.getOutputStream().close();  // Close stdin
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputHandler.accept(line);
                }
            }
            
            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new GooseException("Process timed out after " + timeoutMinutes + " minutes");
            }
            
            if (process.exitValue() != 0) {
                throw new GooseException("Goose exited with code: " + process.exitValue());
            }
            
        } catch (IOException | InterruptedException e) {
            throw new GooseException("Failed to execute Goose", e);
        }
    }
    
    private void copyEnvIfPresent(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            env.put(key, value);
        }
    }
}
```

#### Phase 4: Testing & Documentation (Week 4-5)

**Test Matrix:**

| Test Type | Description |
|-----------|-------------|
| Unit Tests | Detection, installation, configuration parsing |
| Integration Tests | Full buildpack with sample Java app |
| Provider Tests | Verify different LLM providers work |
| Performance Tests | Response time and resource usage |

**Sample Application:**
```java
@RestController
@RequestMapping("/api/goose")
public class GooseController {
    
    private final GooseExecutor gooseExecutor;
    
    public GooseController(GooseExecutor gooseExecutor) {
        this.gooseExecutor = gooseExecutor;
    }
    
    @PostMapping("/execute")
    public ResponseEntity<String> execute(@RequestBody GooseRequest request) {
        try {
            String result = gooseExecutor.execute(request.getPrompt());
            return ResponseEntity.ok(result);
        } catch (GooseException e) {
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }
    
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String prompt) {
        return Flux.create(sink -> {
            try {
                gooseExecutor.executeStreaming(prompt, sink::next);
                sink.complete();
            } catch (GooseException e) {
                sink.error(e);
            }
        });
    }
}
```

### Repository Structure

```
goose-buildpack/
├── bin/
│   ├── detect
│   ├── supply
│   └── finalize
├── lib/
│   ├── installer.sh
│   ├── environment.sh
│   ├── config_parser.py
│   └── validator.sh
├── java-wrapper/
│   ├── pom.xml
│   └── src/main/java/org/tanzu/goose/cf/
│       ├── GooseExecutor.java
│       ├── GooseExecutorImpl.java
│       └── GooseException.java
├── examples/
│   ├── .goose-config.yml
│   └── sample-spring-app/
├── tests/
│   ├── unit/
│   └── integration/
├── README.md
├── QUICKSTART.md
└── manifest.yml
```

### Differences from Claude Code Buildpack

| Aspect | Claude Code Buildpack | Goose Buildpack |
|--------|----------------------|-----------------|
| Runtime | Node.js required | No runtime (native binary) |
| Binary Source | npm install | GitHub releases (tar.bz2) |
| Config File | `.claude-code-config.yml` | `.goose-config.yml` |
| Authentication | `ANTHROPIC_API_KEY` | Multiple providers supported |
| Size | ~100MB (Node + npm) | ~30MB (single binary) |
| CLI Invocation | `claude -p "prompt"` | `goose session --text "prompt"` or `goose session -t "prompt"` |

---

## Project 2: Goose Web UI on Cloud Foundry

### Challenge Analysis

The Goose Desktop application is built with **Electron**, which presents challenges for Cloud Foundry deployment:

1. **Electron is designed for desktop** - It bundles Chromium and Node.js for native desktop apps
2. **Desktop app embeds `goosed`** - The Rust server runs as a child process
3. **No official web UI** - The React frontend assumes Electron IPC, not HTTP

### Feasibility Assessment

| Approach | Feasibility | Effort | Notes |
|----------|-------------|--------|-------|
| Deploy Electron app | ❌ Not feasible | N/A | Requires display, native binaries |
| Extract & adapt React UI | ⚠️ Challenging | High | Requires significant refactoring |
| Build on goose-server | ✅ Feasible | Medium | Server is already HTTP-based |
| Create new web UI | ✅ Feasible | High | Full control, but more work |

### Recommended Approach: Custom Web UI with goose-server

Build a lightweight web application that communicates with `goosed` (goose-server) running in the same container.

#### Architecture

```
┌────────────────────────────────────────────────────────┐
│  Cloud Foundry Container                               │
│                                                        │
│  ┌────────────────────┐     ┌─────────────────────┐   │
│  │  Web Frontend      │────▶│  goosed (Rust)      │   │
│  │  (React/HTML)      │ HTTP│  Port 8080          │   │
│  │  Static files      │     │  API Server         │   │
│  └────────────────────┘     └─────────────────────┘   │
│           │                          │                 │
│           │                          │                 │
│           ▼                          ▼                 │
│  ┌────────────────────────────────────────────────┐   │
│  │               LLM Provider API                  │   │
│  │        (Anthropic, OpenAI, Google, etc.)        │   │
│  └────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

#### Implementation Phases

##### Phase 1: goosed Deployment (Week 1)

**Build goosed for Linux:**
```bash
# Clone repository
git clone https://github.com/block/goose.git
cd goose

# Build goose-server (goosed) for Linux
cargo build --release -p goose-server --target x86_64-unknown-linux-gnu

# Binary location: target/x86_64-unknown-linux-gnu/release/goosed
```

**CF Deployment (Procfile approach):**
```yaml
# manifest.yml
applications:
  - name: goose-web
    memory: 512M
    disk_quota: 1G
    buildpacks:
      - binary_buildpack
    command: ./goosed --port $PORT
    env:
      ANTHROPIC_API_KEY: ((anthropic-api-key))
      GOOSE_PROVIDER__TYPE: anthropic
      GOOSE_PROVIDER__MODEL: claude-sonnet-4-20250514
```

**Verify goosed API:**

The goosed server exposes a comprehensive REST API. Key endpoints include:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/agent/start` | POST | Start a new agent session |
| `/agent/resume` | POST | Resume an existing session |
| `/agent/tools` | GET | List available tools |
| `/agent/add_extension` | POST | Add MCP extension |
| `/agent/call_tool` | POST | Execute a tool |
| `/config` | GET | Get current configuration |
| `/config/providers` | GET | List available LLM providers |
| `/config/set_provider` | POST | Set active provider |
| `/config/extensions` | GET/POST | Manage extensions |
| `/session/messages` | POST | Send message to session |
| `/reply` | POST | Continue agent interaction |

```bash
# Health check
curl http://goose-web.apps.example.com/health

# Start session
curl -X POST http://goose-web.apps.example.com/api/session \
  -H "Content-Type: application/json" \
  -d '{"provider": "anthropic", "model": "claude-sonnet-4-20250514"}'

# Send message
curl -X POST http://goose-web.apps.example.com/api/message \
  -H "Content-Type: application/json" \
  -d '{"session_id": "xxx", "content": "Hello, Goose!"}'
```

##### Phase 2: Minimal Web UI (Week 2-3)

**Option A: Static HTML + JavaScript**

Create a simple web interface using vanilla JavaScript:

```html
<!DOCTYPE html>
<html>
<head>
    <title>Goose Web</title>
    <style>
        body { font-family: system-ui; max-width: 800px; margin: 0 auto; padding: 20px; }
        #chat { height: 500px; overflow-y: auto; border: 1px solid #ccc; padding: 10px; }
        #input { width: 100%; padding: 10px; margin-top: 10px; }
        .message { margin: 10px 0; padding: 10px; border-radius: 5px; }
        .user { background: #e3f2fd; }
        .assistant { background: #f5f5f5; }
    </style>
</head>
<body>
    <h1>🦆 Goose Web</h1>
    <div id="chat"></div>
    <input type="text" id="input" placeholder="Ask Goose something..." />
    <script>
        const API_BASE = '/api';
        let sessionId = null;
        
        async function initSession() {
            const resp = await fetch(`${API_BASE}/session`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });
            const data = await resp.json();
            sessionId = data.session_id;
        }
        
        async function sendMessage(content) {
            const chat = document.getElementById('chat');
            chat.innerHTML += `<div class="message user">${content}</div>`;
            
            const resp = await fetch(`${API_BASE}/message`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ session_id: sessionId, content })
            });
            
            const reader = resp.body.getReader();
            const decoder = new TextDecoder();
            let assistantMessage = '';
            
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                assistantMessage += decoder.decode(value);
            }
            
            chat.innerHTML += `<div class="message assistant">${assistantMessage}</div>`;
            chat.scrollTop = chat.scrollHeight;
        }
        
        document.getElementById('input').addEventListener('keypress', async (e) => {
            if (e.key === 'Enter' && e.target.value) {
                await sendMessage(e.target.value);
                e.target.value = '';
            }
        });
        
        initSession();
    </script>
</body>
</html>
```

**Option B: React Single Page Application**

Adapt components from the Goose Desktop UI:

```jsx
// App.jsx - Simplified Goose Web Interface
import React, { useState, useEffect, useRef } from 'react';

const API_BASE = '/api';

function App() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sessionId, setSessionId] = useState(null);
  const [loading, setLoading] = useState(false);
  const chatRef = useRef(null);

  useEffect(() => {
    // Initialize session on mount
    fetch(`${API_BASE}/session`, { method: 'POST' })
      .then(res => res.json())
      .then(data => setSessionId(data.session_id));
  }, []);

  const sendMessage = async () => {
    if (!input.trim() || !sessionId) return;
    
    const userMessage = { role: 'user', content: input };
    setMessages(prev => [...prev, userMessage]);
    setInput('');
    setLoading(true);

    try {
      const response = await fetch(`${API_BASE}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: sessionId, content: input })
      });

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let assistantContent = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        assistantContent += decoder.decode(value);
        setMessages(prev => [
          ...prev.slice(0, -1),
          { role: 'assistant', content: assistantContent }
        ]);
      }

      setMessages(prev => [
        ...prev.slice(0, prev.length - 1),
        { role: 'assistant', content: assistantContent }
      ]);
    } catch (error) {
      console.error('Error:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="app">
      <header>
        <h1>🦆 Goose Web</h1>
      </header>
      <div className="chat" ref={chatRef}>
        {messages.map((msg, i) => (
          <div key={i} className={`message ${msg.role}`}>
            <strong>{msg.role === 'user' ? 'You' : 'Goose'}:</strong>
            <p>{msg.content}</p>
          </div>
        ))}
        {loading && <div className="loading">Goose is thinking...</div>}
      </div>
      <div className="input-area">
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyPress={e => e.key === 'Enter' && sendMessage()}
          placeholder="Ask Goose something..."
          disabled={loading}
        />
        <button onClick={sendMessage} disabled={loading}>Send</button>
      </div>
    </div>
  );
}

export default App;
```

##### Phase 3: Full-Featured Web UI (Week 4-6)

**Features to implement:**

1. **Session Management** - Multiple sessions, session history
2. **File Operations** - View/edit files in working directory
3. **Extension Support** - Enable/disable Goose extensions
4. **Provider Selection** - Switch between LLM providers
5. **Markdown Rendering** - Proper code formatting
6. **Dark/Light Themes** - User preference

**Deployment Architecture:**

```yaml
# manifest.yml
applications:
  - name: goose-web
    memory: 1G
    disk_quota: 2G
    buildpacks:
      - nodejs_buildpack  # For building React app
      - binary_buildpack  # For goosed binary
    env:
      GOOSE_PORT: 8081  # Internal port for goosed
      PORT: 8080        # External port for web server
    command: |
      ./start.sh
```

**Start Script (start.sh):**
```bash
#!/bin/bash

# Start goosed on internal port
./goosed --port $GOOSE_PORT &
GOOSED_PID=$!

# Wait for goosed to be ready
until curl -s http://localhost:$GOOSE_PORT/health > /dev/null; do
  sleep 1
done

# Start nginx to serve static files and proxy API
nginx -g 'daemon off;'
```

**Nginx Configuration:**
```nginx
server {
    listen $PORT;
    
    # Serve static React build
    location / {
        root /home/vcap/app/build;
        try_files $uri $uri/ /index.html;
    }
    
    # Proxy API requests to goosed
    location /api/ {
        proxy_pass http://127.0.0.1:$GOOSE_PORT/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }
}
```

### Alternative: Contribute Web UI Upstream

Consider contributing a web-deployable UI back to the Goose project:

1. **Create RFC/Issue** - Propose web deployment support
2. **Factor out Electron dependencies** - Make React components web-compatible
3. **Add HTTP transport** - Replace Electron IPC with fetch/WebSocket
4. **Docker support** - Official container image with goosed + web UI

This approach would benefit the broader Goose community and reduce maintenance burden.

---

## Risk Assessment

### Project 1 Risks (Buildpack)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Goose CLI API changes | Medium | High | Pin versions, monitor releases |
| Binary compatibility issues | Low | Medium | Test on CF stemcells |
| Provider authentication failures | Medium | Medium | Clear documentation, validation |
| Resource constraints | Low | Medium | Document memory requirements |

### Project 2 Risks (Web UI)

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| goosed API undocumented | High | High | Analyze source, test extensively |
| Electron dependencies in UI | High | Medium | Extract/rewrite components |
| Performance issues | Medium | Medium | Optimize, add caching |
| Security concerns | Medium | High | Authentication, input validation |

---

## Timeline Summary

| Week | Project 1 (Buildpack) | Project 2 (Web UI) |
|------|----------------------|-------------------|
| 1 | Core buildpack structure | goosed deployment testing |
| 2 | Detection & installation | goosed API documentation |
| 3 | Configuration management | Minimal web UI (static) |
| 4 | Java wrapper library | React UI development |
| 5 | Testing & documentation | Integration & testing |
| 6 | Release & examples | Full-featured UI |

---

## Next Steps

### Immediate Actions (This Week)

1. **Create repository** - `goose-buildpack` on GitHub
2. **Download and test goose CLI** - Verify binary works on Ubuntu 22.04/24.04
3. **Document goosed API** - Analyze OpenAPI spec in `ui/desktop/openapi.json`
4. **Set up CI/CD** - GitHub Actions for buildpack testing

### Questions to Resolve

1. Does Goose support non-interactive/headless mode for the CLI?
2. What is the exact API contract for goosed?
3. Are there plans for an official Goose web UI?
4. What authentication mechanisms does goosed support?

---

## References

- [Goose GitHub Repository](https://github.com/block/goose)
- [Goose Documentation](https://block.github.io/goose/)
- [Claude Code Buildpack (Reference)](https://github.com/cpage-pivotal/claude-code-buildpack)
- [Cloud Foundry Buildpack Documentation](https://docs.cloudfoundry.org/buildpacks/)
- [MCP (Model Context Protocol)](https://modelcontextprotocol.io/)
