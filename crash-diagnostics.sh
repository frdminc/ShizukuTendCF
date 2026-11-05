#!/usr/bin/env bash
# Crash Diagnostics Script for ShizukuTendCF
# This script helps diagnose app startup crashes

set -e

# printf instead of "echo -e" (portable across GNU/Linux and BSD/macOS)
say() { printf '%b\n' "$1"; }

echo "========================================"
echo "ShizukuTendCF Crash Diagnostics"
echo "========================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if device is connected
say "${YELLOW}Checking ADB connection...${NC}"
if ! adb devices | grep -q "device$"; then
    say "${RED}Error: No ADB device connected${NC}"
    exit 1
fi
say "${GREEN}✓ ADB device connected${NC}"
echo ""

# Get device info
say "${YELLOW}Device Information:${NC}"
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
echo ""

# Package name
PACKAGE="af.shizuku.plus.api"

# Check if app is installed
say "${YELLOW}Checking app installation...${NC}"
if adb shell pm list packages | grep -q "$PACKAGE"; then
    say "${GREEN}✓ App is installed${NC}"
else
    say "${RED}✗ App is not installed${NC}"
    echo "Install the app first using: adb install path/to/apk"
    exit 1
fi
echo ""

# Clear logcat
say "${YELLOW}Clearing old logs...${NC}"
adb logcat -c
sleep 1

# Force stop the app
say "${YELLOW}Force stopping app...${NC}"
adb shell am force-stop $PACKAGE
sleep 1

# Clear app data (optional - uncomment if needed)
# say "${YELLOW}Clearing app data...${NC}"
# adb shell pm clear $PACKAGE
# sleep 1

# Start logcat capture in background
say "${YELLOW}Starting crash detection...${NC}"
echo "Watching for crashes in the next 30 seconds..."
echo ""

# Launch the app
say "${YELLOW}Launching app...${NC}"
adb shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1

# Capture logs for 30 seconds
TIMEOUT=30
START_TIME=$(date +%s)
CRASH_DETECTED=false

while true; do
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    
    if [ $ELAPSED -ge $TIMEOUT ]; then
        break
    fi
    
    # Check for crash indicators in logcat
    if adb logcat -d | grep -q "FATAL EXCEPTION"; then
        say "${RED}✗ CRASH DETECTED!${NC}"
        CRASH_DETECTED=true
        break
    fi
    
    if adb logcat -d | grep -q "AndroidRuntime"; then
        say "${RED}✗ AndroidRuntime exception detected!${NC}"
        CRASH_DETECTED=true
        break
    fi
    
    sleep 1
done

# Get full logcat output
echo ""
say "${YELLOW}========================================${NC}"
say "${YELLOW}Full Logcat Output:${NC}"
say "${YELLOW}========================================${NC}"
adb logcat -d | grep -E "(Shizuku|Sentry|ActivityLogManager|AdbStarter|AndroidRuntime|FATAL|ERROR|WARN)" | tail -200

# Save complete logcat to file
LOG_FILE="crash_log_$(date +%Y%m%d_%H%M%S).log"
adb logcat -d > "$LOG_FILE"
echo ""
say "${GREEN}Complete logcat saved to: $LOG_FILE${NC}"

# Check if app is still running
echo ""
say "${YELLOW}Checking app status...${NC}"
if adb shell pidof $PACKAGE > /dev/null 2>&1; then
    say "${GREEN}✓ App is running${NC}"
else
    say "${RED}✗ App has crashed/exited${NC}"
    CRASH_DETECTED=true
fi

# Get tombstone if available
if [ -d /sdcard/tombstones ]; then
    echo ""
    say "${YELLOW}Checking for tombstones...${NC}"
    adb shell ls /sdcard/tombstones/ 2>/dev/null || echo "No tombstones found"
fi

echo ""
say "${YELLOW}========================================${NC}"
if [ "$CRASH_DETECTED" = true ]; then
    say "${RED}CRASH DIAGNOSIS COMPLETE${NC}"
    echo "Please review the logs above and check Sentry for detailed crash reports."
else
    say "${GREEN}NO CRASH DETECTED${NC}"
    echo "App appears to be working correctly."
fi
say "${YELLOW}========================================${NC}"
