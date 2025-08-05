#!/system/bin/sh

# Script to copy OpenCV libraries from APK to system directories
# This resolves library loading issues for apps in /vendor/app/

set -e

# Configuration
APK_PATH="/vendor/app/myjnidemo/app-debug.apk"
LIB_DEST_64="/system/lib64"
LIB_DEST_32="/system/lib"
TEMP_DIR="/tmp/opencv_extract"

# Color output for better readability
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if running as root
check_root() {
    if [ "$(id -u)" != "0" ]; then
        log_error "This script must be run as root"
        exit 1
    fi
}

# Check if APK exists
check_apk() {
    if [ ! -f "$APK_PATH" ]; then
        log_error "APK not found at: $APK_PATH"
        exit 1
    fi
    log_info "Found APK at: $APK_PATH"
}

# Create temporary directory
setup_temp_dir() {
    mkdir -p "$TEMP_DIR"
    log_info "Created temporary directory: $TEMP_DIR"
}

# Extract and copy libraries
extract_and_copy_libs() {
    local abi="$1"
    local lib_dest="$2"
    local lib_path="lib/$abi/libopencv_java4.so"
    
    log_info "Processing $abi libraries..."
    
    # Check if library exists in APK
    if ! unzip -l "$APK_PATH" | grep -q "$lib_path"; then
        log_warn "$lib_path not found in APK, skipping $abi"
        return 0
    fi
    
    # Extract library
    if unzip -j "$APK_PATH" "$lib_path" -d "$TEMP_DIR" > /dev/null 2>&1; then
        log_info "Extracted $lib_path"
    else
        log_error "Failed to extract $lib_path"
        return 1
    fi
    
    # Copy to system directory
    if cp "$TEMP_DIR/libopencv_java4.so" "$lib_dest/"; then
        log_info "Copied to $lib_dest/"
    else
        log_error "Failed to copy to $lib_dest/"
        return 1
    fi
    
    # Set permissions
    chmod 644 "$lib_dest/libopencv_java4.so"
    log_info "Set permissions (644) for $lib_dest/libopencv_java4.so"
    
    # Set SELinux context
    if command -v chcon > /dev/null 2>&1; then
        if chcon u:object_r:system_lib_file:s0 "$lib_dest/libopencv_java4.so"; then
            log_info "Set SELinux context for $lib_dest/libopencv_java4.so"
        else
            log_warn "Failed to set SELinux context (may not be critical)"
        fi
    else
        log_warn "chcon not available, skipping SELinux context setting"
    fi
}

# Verify installation
verify_installation() {
    log_info "Verifying installation..."
    
    for lib_path in "$LIB_DEST_64/libopencv_java4.so" "$LIB_DEST_32/libopencv_java4.so"; do
        if [ -f "$lib_path" ]; then
            log_info "✓ $lib_path exists"
            
            # Check file permissions
            perm=$(stat -c %a "$lib_path")
            log_info "  Permissions: $perm"
            
            # Check SELinux context if available
            if command -v ls > /dev/null 2>&1 && ls --version 2>&1 | grep -q GNU; then
                context=$(ls -Z "$lib_path" 2>/dev/null | awk '{print $1}' || echo "unknown")
                log_info "  SELinux context: $context"
            fi
            
            # Check library dependencies if ldd is available
            if command -v ldd > /dev/null 2>&1; then
                log_info "  Dependencies check:"
                ldd "$lib_path" 2>/dev/null | head -3 | sed 's/^/    /'
            fi
        else
            log_warn "✗ $lib_path not found"
        fi
    done
}

# Cleanup temporary files
cleanup() {
    if [ -d "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
        log_info "Cleaned up temporary directory"
    fi
}

# Backup existing libraries
backup_existing() {
    local backup_dir="/system/lib_backup_$(date +%Y%m%d_%H%M%S)"
    local backed_up=false
    
    for lib_path in "$LIB_DEST_64/libopencv_java4.so" "$LIB_DEST_32/libopencv_java4.so"; do
        if [ -f "$lib_path" ]; then
            if [ "$backed_up" = false ]; then
                mkdir -p "$backup_dir"
                backed_up=true
            fi
            cp "$lib_path" "$backup_dir/"
            log_info "Backed up existing $lib_path to $backup_dir/"
        fi
    done
}

# Main execution
main() {
    log_info "Starting OpenCV library installation..."
    log_info "APK Path: $APK_PATH"
    log_info "Target 64-bit: $LIB_DEST_64"
    log_info "Target 32-bit: $LIB_DEST_32"
    
    # Pre-flight checks
    check_root
    check_apk
    
    # Backup existing libraries
    backup_existing
    
    # Setup
    setup_temp_dir
    
    # Extract and copy libraries
    extract_and_copy_libs "arm64-v8a" "$LIB_DEST_64"
    extract_and_copy_libs "armeabi-v7a" "$LIB_DEST_32"
    
    # Verify installation
    verify_installation
    
    # Cleanup
    cleanup
    
    log_info "OpenCV library installation completed successfully!"
    log_info "You may need to restart the application or reboot the device for changes to take effect."
}

# Trap to ensure cleanup on exit
trap cleanup EXIT

# Run main function
main "$@"