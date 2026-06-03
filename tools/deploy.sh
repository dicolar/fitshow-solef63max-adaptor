#!/bin/bash
# Quick deploy script — run from this directory
set -e
export JAVA_HOME="/Users/Val/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

APK="TreadmillFTMS/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
  echo "Building APK..."
  cd TreadmillFTMS && ./gradlew assembleDebug && cd ..
fi

echo "Installing..."
adb install -r "$APK"
adb shell pm grant com.treadmill.ftms android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.treadmill.ftms android.permission.BLUETOOTH_ADVERTISE
adb shell am stopservice -n com.treadmill.ftms/.FtmsBridgeService 2>/dev/null || true
sleep 1
adb shell am startforegroundservice -n com.treadmill.ftms/.FtmsBridgeService
echo "Done. Showing logs (Ctrl+C to stop):"
adb shell logcat -s FtmsBridge:D
