#!/bin/bash
set -e

BLUE='\033[0;34m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APP_PACKAGE="com.example.peciwearables"

# Garantir que o ADB está no PATH (caso tenhas instalado na home)
export PATH="$HOME/android-sdk/platform-tools:$PATH"

echo -e "${BLUE}Building the latest version...${NC}"
cd "$PROJECT_DIR"
./gradlew :app:assembleDebug
cd - > /dev/null

echo -e "${YELLOW}Installing on the phone...${NC}"

# Tentar instalar. Se falhar por incompatibilidade, desinstalar e tentar de novo.
if ! adb install -r "$APK_PATH" 2>/tmp/adb_err; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" /tmp/adb_err; then
        echo -e "${YELLOW}Incompatible version detected. Reinstalling from scratch...${NC}"
        adb uninstall "$APP_PACKAGE"
        adb install -r "$APK_PATH"
    else
        cat /tmp/adb_err
        echo -e "${RED}✗ Installation error.${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}✓ Success! Starting the app...${NC}"
adb shell am start -n "$APP_PACKAGE/.MainActivity"
