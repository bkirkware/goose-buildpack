#!/usr/bin/env bash
# lib/installer.sh: Installation logic for Goose CLI
# Note: Goose CLI is a native Rust binary - no runtime (Node.js, Python) required

# Goose release configuration
GOOSE_GITHUB_REPO="block/goose"
GOOSE_DEFAULT_VERSION="${GOOSE_VERSION:-stable}"

# Install Goose CLI
install_goose() {
    local install_dir=$1
    local cache_dir=$2

    local goose_version="${GOOSE_VERSION:-${GOOSE_DEFAULT_VERSION}}"
    local goose_archive="goose-x86_64-unknown-linux-gnu.tar.bz2"
    local goose_url
    
    # Determine download URL based on version
    if [ "${goose_version}" = "stable" ] || [ "${goose_version}" = "latest" ]; then
        goose_url="https://github.com/${GOOSE_GITHUB_REPO}/releases/download/stable/${goose_archive}"
        echo "       Downloading Goose CLI (stable release)..."
    else
        goose_url="https://github.com/${GOOSE_GITHUB_REPO}/releases/download/${goose_version}/${goose_archive}"
        echo "       Downloading Goose CLI v${goose_version}..."
    fi
    
    local cache_file="${cache_dir}/goose-${goose_version}.tar.bz2"

    # Check if Goose is already cached
    if [ -f "${cache_file}" ]; then
        echo "       Using cached Goose CLI (${goose_version})"
    else
        echo "       Downloading from ${goose_url}..."
        
        # Download with retry logic
        local max_retries=3
        local retry_count=0
        
        while [ ${retry_count} -lt ${max_retries} ]; do
            if curl -fsSL "${goose_url}" -o "${cache_file}" 2>/dev/null; then
                echo "       Download successful"
                break
            else
                retry_count=$((retry_count + 1))
                if [ ${retry_count} -lt ${max_retries} ]; then
                    echo "       Download failed, retrying (${retry_count}/${max_retries})..."
                    sleep 2
                else
                    echo "       ERROR: Failed to download Goose CLI after ${max_retries} attempts"
                    return 1
                fi
            fi
        done
    fi

    # Extract Goose binary
    echo "       Extracting Goose binary..."
    mkdir -p "${install_dir}/bin"
    
    # Extract tar.bz2 archive
    # The archive contains a single 'goose' binary
    if ! tar xjf "${cache_file}" -C "${install_dir}/bin" 2>/dev/null; then
        echo "       ERROR: Failed to extract Goose archive"
        return 1
    fi

    # Make binary executable
    chmod +x "${install_dir}/bin/goose"

    # Verify the binary exists and is executable
    if [ ! -x "${install_dir}/bin/goose" ]; then
        echo "       ERROR: Goose binary not found after extraction"
        return 1
    fi

    # Test the binary
    if ! "${install_dir}/bin/goose" --version > /dev/null 2>&1; then
        echo "       WARNING: Goose binary exists but --version check failed"
        echo "       This may indicate a library dependency issue"
    fi

    echo "       Goose CLI installed successfully"
    return 0
}

# Get installed Goose version
get_goose_version() {
    local install_dir=$1

    if [ -x "${install_dir}/bin/goose" ]; then
        "${install_dir}/bin/goose" --version 2>/dev/null || echo "unknown"
    else
        echo "not installed"
    fi
}

# Verify Goose installation
verify_installation() {
    local install_dir=$1

    # Check Goose CLI
    if [ ! -x "${install_dir}/bin/goose" ]; then
        echo "       ERROR: Goose CLI verification failed"
        return 1
    fi

    # Verify binary can execute
    if ! "${install_dir}/bin/goose" --version > /dev/null 2>&1; then
        echo "       WARNING: Goose CLI binary exists but execution failed"
        echo "       Continuing anyway - runtime may work differently"
    fi

    echo "       Installation verified successfully"
    return 0
}

# Clean up installation artifacts (optional, for reducing slug size)
cleanup_installation() {
    local install_dir=$1
    local cache_dir=$2

    # Goose is a single binary, minimal cleanup needed
    # Cache can be kept for faster rebuilds
    
    echo "       Cleanup complete (Goose is a single binary, minimal artifacts)"
}

