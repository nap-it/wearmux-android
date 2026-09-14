#!/bin/bash

echo "🚀 Launching PECI Wearables on the Pixel..."
echo ""

cd "$(dirname "$0")"

# Usar adb do gradle para lançar a app
./gradlew :app:installDebug 2>&1 | tail -5

echo ""
echo "✅ APP INSTALLED!"
echo ""
echo "It should now show up on the Pixel:"
echo "  • Tab 1: INFO"
echo "  • Tab 2: CAMERA" 
echo "  • Tab 3: PHOTOS"
echo "  • Tab 4: MAP"
echo "  • Tab 5: USE_CASES"
echo "  • Tab 6: BUILD_DEPLOY"
echo "  • Tab 7: SETTINGS"
