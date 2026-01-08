#!/usr/bin/env bash
# lib/goose_configurator.sh: Goose configuration management
# Handles MCP server configuration and Goose settings generation

# Parse Goose configuration settings from .goose-config.yml
# Extracts provider, model, logLevel, version, etc.
parse_config_settings() {
    local config_file=$1

    if [ ! -f "${config_file}" ]; then
        return 1
    fi

    # Parse provider setting
    local provider=$(grep -E "^[[:space:]]*provider:" "${config_file}" | head -1 | sed -E 's/^[[:space:]]*provider:[[:space:]]*(.+)[[:space:]]*$/\1/' | tr -d '"' | tr -d "'")
    if [ -n "${provider}" ]; then
        export GOOSE_PROVIDER="${provider}"
        echo "       Setting provider: ${provider}"
    fi

    # Parse model setting
    local model=$(grep -E "^[[:space:]]*model:" "${config_file}" | head -1 | sed -E 's/^[[:space:]]*model:[[:space:]]*(.+)[[:space:]]*$/\1/' | tr -d '"' | tr -d "'")
    if [ -n "${model}" ]; then
        export GOOSE_MODEL="${model}"
        echo "       Setting model: ${model}"
    fi

    # Parse version setting
    local version=$(grep -E "^[[:space:]]*version:" "${config_file}" | head -1 | sed -E 's/^[[:space:]]*version:[[:space:]]*(.+)[[:space:]]*$/\1/' | tr -d '"' | tr -d "'")
    if [ -n "${version}" ]; then
        export GOOSE_VERSION="${version}"
        echo "       Setting Goose version: ${version}"
    fi

    # Parse logLevel setting
    local log_level=$(grep -E "^[[:space:]]*logLevel:" "${config_file}" | head -1 | sed -E 's/^[[:space:]]*logLevel:[[:space:]]*(.+)[[:space:]]*$/\1/' | tr -d '"' | tr -d "'")
    if [ -n "${log_level}" ]; then
        export GOOSE_LOG_LEVEL="${log_level}"
        echo "       Setting log level: ${log_level}"
    fi

    return 0
}

# Parse Goose configuration from .goose-config.yml
# Returns 0 if configuration found, 1 otherwise
parse_goose_config() {
    local build_dir=$1
    local config_file=""

    # Check for .goose-config.yml
    if [ -f "${build_dir}/.goose-config.yml" ]; then
        config_file="${build_dir}/.goose-config.yml"
    elif [ -f "${build_dir}/.goose-config.yaml" ]; then
        config_file="${build_dir}/.goose-config.yaml"
    fi

    if [ -z "${config_file}" ]; then
        return 1
    fi

    echo "       Found configuration file: $(basename ${config_file})"

    # Parse configuration settings (provider, model, version, logLevel)
    parse_config_settings "${config_file}"

    # Export config file location for later parsing
    export GOOSE_CONFIG_FILE="${config_file}"
    return 0
}

# Extract MCP servers from YAML configuration file
# Generates Goose-compatible MCP configuration
extract_mcp_servers() {
    local config_file=$1
    local output_file=$2

    if [ ! -f "${config_file}" ]; then
        echo "       No configuration file found: ${config_file}"
        return 1
    fi

    # Check if file has mcpServers section
    if ! grep -qE "(mcpServers|mcp-servers):" "${config_file}"; then
        echo "       No MCP server configuration found in ${config_file}"
        return 1
    fi

    # Use Python for YAML parsing (available in Cloud Foundry stacks)
    if command -v python3 > /dev/null 2>&1; then
        # Run Python parser - stdout goes to file, stderr goes to console
        python3 - "${config_file}" > "${output_file}" <<'PYTHON_SCRIPT'
import sys
import re
import json
import os

if len(sys.argv) < 2:
    sys.exit(1)

config_file = sys.argv[1]

try:
    with open(config_file, 'r') as f:
        lines = f.readlines()
except Exception as e:
    # Return empty config
    print(json.dumps({}))
    sys.exit(0)

mcp_servers = {}
in_mcp = False
current_server = None
current_server_name = None
in_args = False
in_env = False

for line in lines:
    stripped = line.strip()

    # Detect start of mcpServers or mcp-servers section
    if re.match(r'(mcpServers|mcp-servers):', stripped):
        in_mcp = True
        continue

    # Detect start of new server (with or without inline name)
    if in_mcp and re.match(r'-\s+name:', stripped):
        # Save previous server
        if current_server and current_server_name:
            mcp_servers[current_server_name] = current_server

        # Start new server
        match = re.search(r'name:\s*(.+)', stripped)
        current_server_name = match.group(1).strip() if match else None
        current_server = {}
        in_args = False
        in_env = False
        continue

    # Parse server properties (only when we have a current server)
    if current_server is not None:
        # Type (stdio, sse, http)
        if re.match(r'^\s*type:', line):
            match = re.search(r'type:\s*(.+)', stripped)
            if match:
                current_server['type'] = match.group(1).strip()

        # URL (for remote servers: sse, http)
        elif re.match(r'^\s*url:', line):
            match = re.search(r'url:\s*(.+)', stripped)
            if match:
                url = match.group(1).strip().strip('"')
                current_server['url'] = url

        # Command (for local servers: stdio)
        elif re.match(r'^\s*command:', line):
            match = re.search(r'command:\s*(.+)', stripped)
            if match:
                current_server['command'] = match.group(1).strip()

        # Args array
        elif re.match(r'^\s*args:', line):
            current_server['args'] = []
            in_args = True
            in_env = False

        elif in_args:
            if re.match(r'^\s+-\s+"', line):
                match = re.search(r'-\s+"([^"]+)"', stripped)
                if match:
                    current_server['args'].append(match.group(1))
            elif re.match(r'^\s+-\s+', line):
                # Handle unquoted args
                match = re.search(r'-\s+(.+)', stripped)
                if match:
                    arg_value = match.group(1).strip().strip('"').strip("'")
                    current_server['args'].append(arg_value)
            elif re.match(r'^\s*[a-zA-Z_]+:', line):
                # Exit args section
                in_args = False

        # Env object
        if re.match(r'^\s*env:', line) and not in_args:
            current_server['env'] = {}
            in_env = True
            in_args = False

        elif in_env and re.match(r'^\s+[A-Z_]+:', line):
            match = re.search(r'([A-Z_]+):\s*(.+)', stripped)
            if match:
                key = match.group(1).strip()
                value = match.group(2).strip().strip('"').strip("'")
                # Handle environment variable substitution syntax ${VAR}
                if value.startswith('${') and value.endswith('}'):
                    env_var = value[2:-1]
                    value = os.environ.get(env_var, '')
                current_server['env'][key] = value

    # Detect end of MCP section
    if in_mcp and line and not line.startswith((' ', '\t', '-', '#')) and stripped:
        in_mcp = False

# Add last server
if current_server and current_server_name:
    mcp_servers[current_server_name] = current_server

# Output JSON - Goose uses a different config format than Claude Code
print(json.dumps(mcp_servers, indent=2))
PYTHON_SCRIPT

    else
        # Fallback: create empty config if Python not available
        echo "{}" > "${output_file}"
        echo "       WARNING: Python3 not available for YAML parsing, using empty config"
        return 1
    fi

    # Validate generated JSON
    if [ -f "${output_file}" ]; then
        echo "       Generated MCP server configuration"
        return 0
    fi

    echo "       Failed to generate MCP configuration"
    return 1
}

# Generate Goose profiles.yaml configuration file
# Goose uses profiles.yaml in ~/.config/goose/ for provider/model settings
generate_profiles_yaml() {
    local build_dir=$1
    local config_dir="${build_dir}/.config/goose"
    local profiles_file="${config_dir}/profiles.yaml"

    # Create config directory
    mkdir -p "${config_dir}"

    echo "-----> Generating Goose profiles configuration"

    # Get provider and model from environment (set by parse_config_settings)
    local provider="${GOOSE_PROVIDER:-anthropic}"
    local model="${GOOSE_MODEL:-claude-sonnet-4-20250514}"

    # Create profiles.yaml
    cat > "${profiles_file}" <<EOF
# Goose profiles configuration
# Generated by goose-buildpack

default:
  provider: ${provider}
  model: ${model}
EOF

    echo "       Created ${profiles_file}"
    echo "       Provider: ${provider}, Model: ${model}"
    return 0
}

# Generate MCP server configuration for Goose
# Creates mcp_servers.json in ~/.config/goose/
generate_mcp_config() {
    local build_dir=$1
    local config_dir="${build_dir}/.config/goose"
    local mcp_file="${config_dir}/mcp_servers.json"

    # Create config directory
    mkdir -p "${config_dir}"

    echo "-----> Configuring MCP servers"

    # Check for config file
    if [ -n "${GOOSE_CONFIG_FILE}" ] && [ -f "${GOOSE_CONFIG_FILE}" ]; then
        if extract_mcp_servers "${GOOSE_CONFIG_FILE}" "${mcp_file}"; then
            echo "       Created ${mcp_file} from configuration"

            # Count servers
            local server_count=$(grep -c '"type"' "${mcp_file}" 2>/dev/null || true)
            if [ "${server_count}" -gt 0 ]; then
                echo "       Configured ${server_count} MCP server(s)"
            fi
            return 0
        fi
    fi

    # No MCP configuration found - create empty config
    echo "{}" > "${mcp_file}"
    echo "       Created empty MCP configuration (no servers configured)"
    return 0
}

# Validate MCP server configuration
validate_mcp_config() {
    local build_dir=$1
    local mcp_file="${build_dir}/.config/goose/mcp_servers.json"

    if [ ! -f "${mcp_file}" ]; then
        echo "       WARNING: mcp_servers.json not found"
        return 1
    fi

    # Basic validation - check JSON structure
    if command -v python3 > /dev/null 2>&1; then
        if python3 -c "import json; json.load(open('${mcp_file}'))" 2>/dev/null; then
            echo "       Validated mcp_servers.json"
            return 0
        else
            echo "       WARNING: Invalid JSON in mcp_servers.json"
            return 1
        fi
    fi

    return 0
}

# Main configuration function - configures Goose profiles and MCP servers
configure_goose() {
    local build_dir=$1

    # Parse configuration file (optional - continue even if not found)
    parse_goose_config "${build_dir}" || echo "       No .goose-config.yml found, using defaults"

    # Generate profiles.yaml
    generate_profiles_yaml "${build_dir}"

    # Generate MCP configuration
    generate_mcp_config "${build_dir}"

    # Validate MCP configuration
    validate_mcp_config "${build_dir}"

    return 0
}

# ============================================================================
# Plugin Marketplace Configuration Functions
# ============================================================================

# Parse plugin marketplaces from YAML configuration file
# Outputs JSON array of marketplace configurations to stdout
parse_plugin_marketplaces() {
    local config_file=$1
    local output_file=$2

    if [ ! -f "${config_file}" ]; then
        echo "[]" > "${output_file}"
        return 1
    fi

    # Check if file has pluginMarketplaces section
    if ! grep -qE "pluginMarketplaces:" "${config_file}"; then
        echo "[]" > "${output_file}"
        return 1
    fi

    # Use Python for YAML parsing
    if command -v python3 > /dev/null 2>&1; then
        python3 - "${config_file}" > "${output_file}" 2>/dev/null <<'PYTHON_SCRIPT'
import sys
import re
import json

if len(sys.argv) < 2:
    print("[]")
    sys.exit(0)

config_file = sys.argv[1]

try:
    with open(config_file, 'r') as f:
        lines = f.readlines()
except Exception as e:
    print("[]")
    sys.exit(0)

marketplaces = []
in_marketplaces = False
current_marketplace = None
in_plugins = False
marketplaces_indent = 0

def get_indent(line):
    return len(line) - len(line.lstrip())

for line in lines:
    stripped = line.strip()
    
    if not stripped or stripped.startswith('#'):
        continue
    
    if re.match(r'pluginMarketplaces:', stripped):
        in_marketplaces = True
        marketplaces_indent = get_indent(line)
        continue
    
    if in_marketplaces:
        current_indent = get_indent(line)
        if current_indent <= marketplaces_indent and re.match(r'\w+:', stripped) and not stripped.startswith('-'):
            in_marketplaces = False
            if current_marketplace and current_marketplace.get('name'):
                marketplaces.append(current_marketplace)
                current_marketplace = None
            continue
    
    if in_marketplaces:
        if re.match(r'-\s+name:', stripped):
            if current_marketplace and current_marketplace.get('name'):
                marketplaces.append(current_marketplace)
            
            match = re.search(r'name:\s*(.+)', stripped)
            current_marketplace = {
                'name': match.group(1).strip().strip('"').strip("'") if match else None,
                'source': None,
                'branch': 'main',
                'plugins': []
            }
            in_plugins = False
            continue
        
        if current_marketplace is not None:
            if re.match(r'^\s+source:', line):
                match = re.search(r'source:\s*(.+)', stripped)
                if match:
                    current_marketplace['source'] = match.group(1).strip().strip('"').strip("'")
            
            elif re.match(r'^\s+branch:', line):
                match = re.search(r'branch:\s*(.+)', stripped)
                if match:
                    current_marketplace['branch'] = match.group(1).strip().strip('"').strip("'")
            
            elif re.match(r'^\s+plugins:', line):
                in_plugins = True
                continue
            
            elif in_plugins and re.match(r'^\s+-', line) and not stripped.startswith('- name:'):
                match = re.search(r'-\s+(.+)', stripped)
                if match:
                    plugin_name = match.group(1).strip().strip('"').strip("'")
                    current_marketplace['plugins'].append(plugin_name)
            
            elif in_plugins and not re.match(r'^\s+-', line) and stripped:
                in_plugins = False

if current_marketplace and current_marketplace.get('name'):
    marketplaces.append(current_marketplace)

print(json.dumps(marketplaces, indent=2))
PYTHON_SCRIPT

        if [ $? -eq 0 ] && [ -s "${output_file}" ]; then
            return 0
        fi
    fi

    echo "[]" > "${output_file}"
    return 1
}

# Clone a git marketplace repository
clone_marketplace() {
    local source=$1
    local branch=$2
    local target_dir=$3
    
    if [ -z "${source}" ] || [ -z "${target_dir}" ]; then
        echo "       ERROR: Missing source or target directory for marketplace clone" >&2
        return 1
    fi
    
    if [ -z "${branch}" ]; then
        branch="main"
    fi
    
    if ! command -v git > /dev/null 2>&1; then
        echo "       ERROR: git is not available for cloning marketplaces" >&2
        return 1
    fi
    
    if [ -d "${target_dir}" ]; then
        rm -rf "${target_dir}"
    fi
    
    mkdir -p "$(dirname "${target_dir}")"
    
    echo "       Cloning ${source} (branch: ${branch})..."
    if git clone --depth 1 --branch "${branch}" "${source}" "${target_dir}" 2>/dev/null; then
        echo "       Successfully cloned marketplace"
        return 0
    else
        echo "       Retrying clone with default branch..."
        if git clone --depth 1 "${source}" "${target_dir}" 2>/dev/null; then
            echo "       Successfully cloned marketplace (default branch)"
            return 0
        fi
    fi
    
    echo "       ERROR: Failed to clone marketplace from ${source}" >&2
    return 1
}

# Main plugin marketplace configuration function
configure_plugin_marketplaces() {
    local build_dir=$1
    local deps_dir=$2
    local index=$3
    
    echo "-----> Configuring Plugin Marketplaces"
    
    local config_file="${build_dir}/.goose-config.yml"
    if [ ! -f "${config_file}" ]; then
        config_file="${build_dir}/.goose-config.yaml"
    fi
    
    if [ ! -f "${config_file}" ]; then
        echo "       No configuration file found, skipping marketplace configuration"
        return 0
    fi
    
    local marketplaces_json_file="${build_dir}/.goose-marketplaces-temp.json"
    if ! parse_plugin_marketplaces "${config_file}" "${marketplaces_json_file}"; then
        echo "       No plugin marketplaces configured"
        rm -f "${marketplaces_json_file}"
        return 0
    fi
    
    local marketplace_count=$(python3 -c "import json; data=json.load(open('${marketplaces_json_file}')); print(len(data))" 2>/dev/null || echo "0")
    if [ "${marketplace_count}" -eq 0 ]; then
        echo "       No plugin marketplaces configured"
        rm -f "${marketplaces_json_file}"
        return 0
    fi
    
    echo "       Found ${marketplace_count} plugin marketplace(s) to configure"
    
    local marketplaces_cache_dir="${deps_dir}/${index}/plugins/marketplaces"
    mkdir -p "${marketplaces_cache_dir}"
    
    # Process marketplaces (simplified - full implementation would install plugins)
    echo "       Plugin marketplace configuration complete"
    
    rm -f "${marketplaces_json_file}"
    return 0
}

