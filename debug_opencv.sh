#!/system/bin/sh

# Debug script for OpenCV library loading issues
# Helps diagnose why libopencv_java4.so cannot be loaded

# Configuration
APK_PATH="/vendor/app/myjnidemo/app-debug.apk"
PACKAGE_NAME="com.crescent.myjnidemo"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_section() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check APK existence and structure
check_apk_structure() {
    log_section "APK Structure Analysis"
    
    if [ ! -f "$APK_PATH" ]; then
        log_error "APK not found at: $APK_PATH"
        return 1
    fi
    
    log_info "APK found at: $APK_PATH"
    log_info "APK size: $(stat -c %s "$APK_PATH") bytes"
    
    log_info "Checking for OpenCV libraries in APK:"
    if unzip -l "$APK_PATH" | grep -E "lib/.*/libopencv"; then
        log_info "✓ OpenCV libraries found in APK"
    else
        log_error "✗ No OpenCV libraries found in APK"
    fi
    
    log_info "Full library listing:"
    unzip -l "$APK_PATH" | grep "lib/" | head -20
}

# Check system library paths
check_system_libraries() {
    log_section "System Library Paths"
    
    local paths=(
        "/system/lib64/libopencv_java4.so"
        "/system/lib/libopencv_java4.so"
        "/vendor/lib64/libopencv_java4.so"
        "/vendor/lib/libopencv_java4.so"
    )
    
    for path in "${paths[@]}"; do
        if [ -f "$path" ]; then
            log_info "✓ $path exists"
            log_info "  Size: $(stat -c %s "$path") bytes"
            log_info "  Permissions: $(stat -c %a "$path")"
            if command -v ls > /dev/null 2>&1 && ls --version 2>&1 | grep -q GNU; then
                context=$(ls -Z "$path" 2>/dev/null | awk '{print $1}' || echo "unknown")
                log_info "  SELinux context: $context"
            fi
        else
            log_warn "✗ $path not found"
        fi
    done
}

# Check library dependencies
check_library_dependencies() {
    log_section "Library Dependencies"
    
    local libs=(
        "/system/lib64/libopencv_java4.so"
        "/system/lib/libopencv_java4.so"
    )
    
    for lib in "${libs[@]}"; do
        if [ -f "$lib" ]; then
            log_info "Dependencies for $lib:"
            if command -v ldd > /dev/null 2>&1; then
                ldd "$lib" 2>/dev/null | sed 's/^/  /' || log_warn "ldd failed for $lib"
            elif command -v readelf > /dev/null 2>&1; then
                log_info "  Using readelf to check dependencies:"
                readelf -d "$lib" 2>/dev/null | grep NEEDED | sed 's/^/  /' || log_warn "readelf failed for $lib"
            else
                log_warn "Neither ldd nor readelf available"
            fi
        fi
    done
}

# Check namespace and class loader information
check_namespace_info() {
    log_section "Namespace and Class Loader Information"
    
    log_info "Current process namespaces:"
    if [ -d "/proc/self/ns" ]; then
        ls -la /proc/self/ns/ 2>/dev/null | sed 's/^/  /' || log_warn "Cannot read namespace info"
    fi
    
    log_info "Library search paths for current user:"
    if command -v ldconfig > /dev/null 2>&1; then
        ldconfig -p 2>/dev/null | grep opencv | sed 's/^/  /' || log_info "  No opencv libraries in ldconfig cache"
    fi
    
    # Check if app is running
    if pidof "$PACKAGE_NAME" > /dev/null 2>&1; then
        local pid=$(pidof "$PACKAGE_NAME")
        log_info "App is running with PID: $pid"
        log_info "Memory maps:"
        cat "/proc/$pid/maps" 2>/dev/null | grep -E "(lib|opencv)" | head -10 | sed 's/^/  /' || log_warn "Cannot read memory maps"
    else
        log_info "App is not currently running"
    fi
}

# Check SELinux policies
check_selinux() {
    log_section "SELinux Configuration"
    
    if command -v getenforce > /dev/null 2>&1; then
        local mode=$(getenforce 2>/dev/null || echo "unknown")
        log_info "SELinux mode: $mode"
    else
        log_warn "getenforce not available"
    fi
    
    if command -v getsebool > /dev/null 2>&1; then
        log_info "Relevant SELinux booleans:"
        getsebool -a 2>/dev/null | grep -E "(app|lib|vendor)" | head -5 | sed 's/^/  /' || log_warn "Cannot read SELinux booleans"
    fi
    
    # Check file contexts
    if command -v ls > /dev/null 2>&1 && ls --version 2>&1 | grep -q GNU; then
        log_info "File contexts for /vendor/app/:"
        ls -Z "$APK_PATH" 2>/dev/null | sed 's/^/  /' || log_warn "Cannot read file context for APK"
    fi
}

# Check Android environment
check_android_environment() {
    log_section "Android Environment"
    
    log_info "Android version: $(getprop ro.build.version.release 2>/dev/null || echo "unknown")"
    log_info "SDK version: $(getprop ro.build.version.sdk 2>/dev/null || echo "unknown")"
    log_info "ABI: $(getprop ro.product.cpu.abi 2>/dev/null || echo "unknown")"
    log_info "Architecture: $(uname -m)"
    
    log_info "Library loader information:"
    getprop | grep -E "(lib|loader|namespace)" | head -5 | sed 's/^/  /' || log_warn "Cannot read system properties"
}

# Extract and analyze APK libraries
extract_and_analyze() {
    log_section "APK Library Analysis"
    
    local temp_dir="/tmp/opencv_debug_$$"
    mkdir -p "$temp_dir"
    
    log_info "Extracting libraries to: $temp_dir"
    
    # Extract all OpenCV libraries
    if unzip -j "$APK_PATH" "lib/*/libopencv*" -d "$temp_dir" > /dev/null 2>&1; then
        log_info "Extracted libraries:"
        ls -la "$temp_dir"/ | sed 's/^/  /'
        
        for lib in "$temp_dir"/libopencv*; do
            if [ -f "$lib" ]; then
                log_info "Analyzing $(basename "$lib"):"
                log_info "  Size: $(stat -c %s "$lib") bytes"
                
                if command -v file > /dev/null 2>&1; then
                    file_info=$(file "$lib" 2>/dev/null || echo "unknown")
                    log_info "  Type: $file_info"
                fi
                
                if command -v readelf > /dev/null 2>&1; then
                    log_info "  Architecture: $(readelf -h "$lib" 2>/dev/null | grep Machine | awk '{print $2}' || echo "unknown")"
                    log_info "  Dependencies:"
                    readelf -d "$lib" 2>/dev/null | grep NEEDED | sed 's/^/    /' || log_warn "Cannot read dependencies"
                fi
            fi
        done
    else
        log_warn "Failed to extract libraries from APK"
    fi
    
    # Cleanup
    rm -rf "$temp_dir"
}

# Check logcat for relevant messages
check_logcat() {
    log_section "Logcat Analysis"
    
    log_info "Recent OpenCV related log entries:"
    if command -v logcat > /dev/null 2>&1; then
        logcat -d | grep -i opencv | tail -10 | sed 's/^/  /' || log_info "  No recent OpenCV logs found"
        
        log_info "Recent library loading errors:"
        logcat -d | grep -E "(dlopen|UnsatisfiedLinkError|library.*not found)" | tail -5 | sed 's/^/  /' || log_info "  No recent library loading errors"
    else
        log_warn "logcat not available"
    fi
}

# Generate summary and recommendations
generate_summary() {
    log_section "Summary and Recommendations"
    
    log_info "Diagnosis complete. Recommendations:"
    
    # Check if libraries exist in APK
    if ! unzip -l "$APK_PATH" | grep -q "lib/.*/libopencv"; then
        log_error "1. Libraries missing from APK - rebuild with OpenCV included"
    else
        log_info "1. ✓ Libraries present in APK"
    fi
    
    # Check if libraries exist in system
    if [ ! -f "/system/lib64/libopencv_java4.so" ] && [ ! -f "/system/lib/libopencv_java4.so" ]; then
        log_warn "2. Consider copying libraries to system directories using copy_opencv_libs.sh"
    else
        log_info "2. ✓ Libraries found in system directories"
    fi
    
    # Check AndroidManifest
    log_warn "3. Ensure AndroidManifest.xml has android:extractNativeLibs=\"true\""
    
    # Check build configuration
    log_warn "4. Verify build.gradle has proper packagingOptions and ndk configuration"
    
    # Check Java code
    log_warn "5. Use OpenCVLoader helper class for robust library loading"
    
    log_info "\nFor immediate resolution, try:"
    log_info "  - Run copy_opencv_libs.sh as root"
    log_info "  - Use OpenCVLoader.java in your application"
    log_info "  - Add android:extractNativeLibs=\"true\" to AndroidManifest.xml"
}

# Main execution
main() {
    echo -e "${BLUE}OpenCV Library Loading Debug Tool${NC}"
    echo "Analyzing: $APK_PATH"
    echo "Package: $PACKAGE_NAME"
    
    check_apk_structure
    check_system_libraries
    check_library_dependencies
    check_namespace_info
    check_selinux
    check_android_environment
    extract_and_analyze
    check_logcat
    generate_summary
    
    log_info "\nDebug analysis complete!"
}

main "$@"