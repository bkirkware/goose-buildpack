#!/usr/bin/env bash
# lib/environment.sh: Environment variable setup and configuration for Goose CLI

# Set up environment variables for Goose
setup_environment() {
    local deps_dir=$1
    local build_dir=$2
    local index=$3

    # Create profile.d directory in BUILD_DIR (not DEPS_DIR!)
    # Cloud Foundry sources scripts from /home/vcap/app/.profile.d/ at runtime
    mkdir -p "${build_dir}/.profile.d"
    local profile_script="${build_dir}/.profile.d/goose-env.sh"

    # Use the actual index number in the script (not ${DEPS_INDEX} which may not be set at runtime)
    cat > "${profile_script}" <<EOF
# Goose CLI environment configuration

# Add Goose to PATH
export PATH="\$DEPS_DIR/${index}/bin:\$PATH"

# Set Goose CLI path for Java applications
export GOOSE_CLI_PATH="\$DEPS_DIR/${index}/bin/goose"

# Set home directory for Goose configuration
export GOOSE_CONFIG_HOME="\$HOME"

# Goose configuration directory
export GOOSE_CONFIG_DIR="\${HOME}/.config/goose"
mkdir -p "\$GOOSE_CONFIG_DIR"

# Copy Goose configuration from app directory to HOME (where Goose expects it)
# The buildpack generates config.yaml and profiles.yaml in /home/vcap/app/.config/goose/
# but Goose looks for them in ~/.config/goose/
if [ -d "/home/vcap/app/.config/goose" ]; then
    echo "[goose-env] Copying Goose configuration to \$HOME/.config/goose/"
    cp -r /home/vcap/app/.config/goose/* "\$GOOSE_CONFIG_DIR/" 2>/dev/null || true
    
    # Debug: Show what config files are available
    echo "[goose-env] Configuration files in \$GOOSE_CONFIG_DIR:"
    ls -la "\$GOOSE_CONFIG_DIR/" 2>/dev/null || echo "[goose-env] (none found)"
    
    # Debug: Show config.yaml contents if it exists
    if [ -f "\$GOOSE_CONFIG_DIR/config.yaml" ]; then
        echo "[goose-env] config.yaml contents:"
        cat "\$GOOSE_CONFIG_DIR/config.yaml"
    fi
fi

# Provider configuration
# Supported providers: openai, anthropic, google, databricks, ollama, etc.
# These are set from config file or environment variables

# Priority: config file > environment variable > default
if [ -n "\$GOOSE_PROVIDER" ]; then
    export GOOSE_PROVIDER__TYPE="\${GOOSE_PROVIDER}"
fi

if [ -n "\$GOOSE_MODEL" ]; then
    export GOOSE_PROVIDER__MODEL="\${GOOSE_MODEL}"
fi

# Log level configuration (from config file or default)
if [ -z "\$GOOSE_LOG_LEVEL" ]; then
    export GOOSE_LOG_LEVEL="${GOOSE_LOG_LEVEL:-info}"
fi

# Timeout configuration for Java wrapper (default: 5 minutes)
if [ -z "\$GOOSE_TIMEOUT_MINUTES" ]; then
    export GOOSE_TIMEOUT_MINUTES="${GOOSE_TIMEOUT_MINUTES:-5}"
fi

# Max turns configuration (prevents runaway sessions)
if [ -z "\$GOOSE_MAX_TURNS" ]; then
    export GOOSE_MAX_TURNS="${GOOSE_MAX_TURNS:-100}"
fi
EOF

    chmod +x "${profile_script}"

    # Export environment for build time
    export PATH="${deps_dir}/bin:${PATH}"
    export GOOSE_CLI_PATH="${deps_dir}/bin/goose"
}

# Create configuration files
create_config_files() {
    local deps_dir=$1
    local build_dir=$2

    # Create buildpack config file
    local config_file="${deps_dir}/config.yml"

    cat > "${config_file}" <<EOF
---
name: goose-buildpack
config:
  version: ${GOOSE_VERSION:-stable}
  cli_path: ${deps_dir}/bin/goose
  config_home: /home/vcap/app
EOF

    # Note: Goose profiles.yaml is created by the Goose configurator (lib/goose_configurator.sh)
}

# Get Goose version (if needed for other scripts)
get_goose_version_string() {
    echo "${GOOSE_VERSION:-stable}"
}

# Set up provider authentication
# Goose supports multiple providers: anthropic, openai, google, databricks, ollama, etc.
setup_provider_auth() {
    local deps_dir=$1

    # Check for provider-specific API keys
    local has_auth=false
    local auth_providers=""

    if [ -n "${ANTHROPIC_API_KEY}" ]; then
        echo "       Anthropic API key detected"
        auth_providers="${auth_providers} anthropic"
        has_auth=true
    fi

    if [ -n "${OPENAI_API_KEY}" ]; then
        echo "       OpenAI API key detected"
        auth_providers="${auth_providers} openai"
        has_auth=true
    fi

    if [ -n "${GOOGLE_API_KEY}" ]; then
        echo "       Google API key detected"
        auth_providers="${auth_providers} google"
        has_auth=true
    fi

    if [ -n "${DATABRICKS_HOST}" ] && [ -n "${DATABRICKS_TOKEN}" ]; then
        echo "       Databricks credentials detected"
        auth_providers="${auth_providers} databricks"
        has_auth=true
    fi

    if [ -n "${OLLAMA_HOST}" ]; then
        echo "       Ollama host detected"
        auth_providers="${auth_providers} ollama"
        has_auth=true
    fi

    if [ "${has_auth}" = false ]; then
        echo "       WARNING: No LLM provider credentials detected"
        echo "       Goose requires at least one provider API key to function"
        echo "       Supported: ANTHROPIC_API_KEY, OPENAI_API_KEY, GOOGLE_API_KEY, DATABRICKS_*, OLLAMA_HOST"
        return 1
    fi

    echo "       Available providers:${auth_providers}"
    return 0
}

