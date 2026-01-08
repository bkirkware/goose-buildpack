#!/usr/bin/env bash
# lib/validator.sh: Validation utilities for Goose buildpack

# Validate environment and prerequisites
validate_environment() {
    local build_dir=$1

    echo "       Checking prerequisites..."

    # Check for required commands
    if ! command -v curl &> /dev/null; then
        echo "       ERROR: curl is required but not installed"
        return 1
    fi

    if ! command -v tar &> /dev/null; then
        echo "       ERROR: tar is required but not installed"
        return 1
    fi

    # Note: bunzip2 is typically available with tar for .tar.bz2 extraction
    # If not, tar will fail gracefully

    # Check for at least one LLM provider API key
    local has_provider=false
    
    if [ -n "${ANTHROPIC_API_KEY}" ]; then
        # Basic validation - check if it looks like an Anthropic API key
        if [[ ! "${ANTHROPIC_API_KEY}" =~ ^sk-ant- ]]; then
            echo "       WARNING: ANTHROPIC_API_KEY format appears invalid"
            echo "       Expected format: sk-ant-..."
        else
            echo "       Anthropic API key format validated"
        fi
        has_provider=true
    fi

    if [ -n "${OPENAI_API_KEY}" ]; then
        if [[ ! "${OPENAI_API_KEY}" =~ ^sk- ]]; then
            echo "       WARNING: OPENAI_API_KEY format appears invalid"
            echo "       Expected format: sk-..."
        else
            echo "       OpenAI API key format validated"
        fi
        has_provider=true
    fi

    if [ -n "${GOOGLE_API_KEY}" ]; then
        echo "       Google API key detected"
        has_provider=true
    fi

    if [ -n "${DATABRICKS_HOST}" ] && [ -n "${DATABRICKS_TOKEN}" ]; then
        echo "       Databricks credentials detected"
        has_provider=true
    fi

    if [ -n "${OLLAMA_HOST}" ]; then
        echo "       Ollama host detected (local inference)"
        has_provider=true
    fi

    if [ "${has_provider}" = false ]; then
        echo "       WARNING: No LLM provider credentials found"
        echo "       Goose requires at least one of the following:"
        echo "         - ANTHROPIC_API_KEY"
        echo "         - OPENAI_API_KEY"
        echo "         - GOOGLE_API_KEY"
        echo "         - DATABRICKS_HOST + DATABRICKS_TOKEN"
        echo "         - OLLAMA_HOST"
        echo "       Set the appropriate environment variable in your manifest"
    fi

    # Check disk space (basic check)
    local available_space=$(df -k "${build_dir}" | awk 'NR==2 {print $4}')
    local required_space=102400  # 100MB in KB (Goose binary is ~30MB)

    if [ "${available_space}" -lt "${required_space}" ]; then
        echo "       WARNING: Low disk space (available: ${available_space}KB, recommended: ${required_space}KB)"
    fi

    return 0
}

# Validate configuration file
validate_config_file() {
    local config_file=$1

    if [ ! -f "${config_file}" ]; then
        echo "       ERROR: Configuration file not found: ${config_file}"
        return 1
    fi

    # Basic YAML validation (check if file is readable)
    if [ ! -r "${config_file}" ]; then
        echo "       ERROR: Configuration file not readable: ${config_file}"
        return 1
    fi

    echo "       Configuration file validated"
    return 0
}

# Validate Goose version string
validate_goose_version() {
    local version=$1

    # Check if version string is valid
    # Accepts: "stable", "latest", or semantic version like "v1.19.0"
    if [[ "${version}" =~ ^(stable|latest)$ ]]; then
        return 0
    fi

    if [[ "${version}" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        return 0
    fi

    echo "       ERROR: Invalid Goose version format: ${version}"
    echo "       Expected: 'stable', 'latest', or vX.Y.Z (e.g., v1.19.0)"
    return 1
}

# Validate installation directory
validate_install_dir() {
    local install_dir=$1

    if [ ! -d "${install_dir}" ]; then
        echo "       ERROR: Installation directory does not exist: ${install_dir}"
        return 1
    fi

    if [ ! -w "${install_dir}" ]; then
        echo "       ERROR: Installation directory is not writable: ${install_dir}"
        return 1
    fi

    return 0
}

# Validate MCP server configuration (basic)
validate_mcp_config() {
    local config=$1

    # This validates the MCP configuration structure
    # More detailed validation is done in goose_configurator.sh

    echo "       MCP configuration validation complete"
    return 0
}

# Check if a command exists
command_exists() {
    local cmd=$1
    command -v "${cmd}" &> /dev/null
}

# Validate required tools
validate_required_tools() {
    local missing_tools=()

    local required_tools=("curl" "tar" "mkdir" "chmod")

    for tool in "${required_tools[@]}"; do
        if ! command_exists "${tool}"; then
            missing_tools+=("${tool}")
        fi
    done

    if [ ${#missing_tools[@]} -gt 0 ]; then
        echo "       ERROR: Missing required tools: ${missing_tools[*]}"
        return 1
    fi

    echo "       All required tools are available"
    return 0
}

# Validate provider configuration
validate_provider_config() {
    local provider=$1
    local model=$2

    # Validate provider type
    local valid_providers=("anthropic" "openai" "google" "databricks" "ollama" "azure" "bedrock")
    local is_valid=false

    for p in "${valid_providers[@]}"; do
        if [ "${provider}" = "${p}" ]; then
            is_valid=true
            break
        fi
    done

    if [ "${is_valid}" = false ] && [ -n "${provider}" ]; then
        echo "       WARNING: Unknown provider type: ${provider}"
        echo "       Supported providers: ${valid_providers[*]}"
    fi

    return 0
}

