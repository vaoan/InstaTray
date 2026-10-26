#!/bin/bash
# Builds InstaTray.app (menu-bar app) next to this script.
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVAC="$JDK/bin/javac"
JAR="$JDK/bin/jar"
JAVA="$JDK/bin/java"

rm -rf build InstaTray.app
mkdir -p build/classes

# --release 21: runs on any recent Java that Homebrew installs
"$JAVAC" --release 21 -d build/classes src/*.java
"$JAR" --create --file build/InstaTray.jar --main-class InstaTray -C build/classes .

# Icon: render PNGs with Java, pack with iconutil
"$JAVA" -cp build/classes -Djava.awt.headless=true AeroIcon build/InstaTray.iconset
iconutil -c icns build/InstaTray.iconset -o build/InstaTray.icns
cp build/InstaTray.iconset/icon_512x512@2x.png icon.png

APP=InstaTray.app/Contents
mkdir -p "$APP/MacOS" "$APP/Resources"
cp build/InstaTray.jar "$APP/Resources/"

# Native menu-bar item (narrower than Java's TrayIcon); without swiftc the app falls back to TrayIcon
if command -v swiftc >/dev/null; then
  swiftc -O src/InstaTrayBar.swift -o "$APP/Resources/InstaTrayBar"
else
  echo "swiftc not found: InstaTrayBar not built (Java tray icon will be used)"
fi
cp build/InstaTray.icns "$APP/Resources/"

# Launcher checks/installs the requirements (Homebrew, Java, yt-dlp, ffmpeg, deno) and starts Java detached
cp src/launcher.sh "$APP/MacOS/InstaTray"
cp src/setup.command "$APP/Resources/setup.command"
chmod +x "$APP/MacOS/InstaTray" "$APP/Resources/setup.command"

cat > "$APP/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>InstaTray</string>
  <key>CFBundleDisplayName</key><string>InstaTray</string>
  <key>CFBundleIdentifier</key><string>local.instatray</string>
  <key>CFBundleVersion</key><string>1.0</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleExecutable</key><string>InstaTray</string>
  <key>CFBundleIconFile</key><string>InstaTray</string>
  <key>LSArchitecturePriority</key>
  <array><string>arm64</string></array>
  <key>LSUIElement</key><true/>
</dict>
</plist>
EOF

echo "Built $(pwd)/InstaTray.app"

# Windows package: the same jar + an .ico + the installer scripts (windows/), zipped
WIN=dist/InstaTray-Windows
rm -rf dist
mkdir -p "$WIN"
cp build/InstaTray.jar windows/* "$WIN/"
"$JAVA" -cp build/classes -Djava.awt.headless=true AeroIcon --ico "$WIN/InstaTray.ico"
(cd dist && zip -qr InstaTray-Windows.zip InstaTray-Windows)
echo "Built $(pwd)/dist/InstaTray-Windows.zip"

codesign --force --deep -s - InstaTray.app

# Double-click shortcut in ~/Documents
LINK="$HOME/Documents/InstaTray.app"
if [ -L "$LINK" ] || [ ! -e "$LINK" ]; then
  ln -sfn "$(pwd)/InstaTray.app" "$LINK"
  echo "Shortcut: $LINK"
fi
touch InstaTray.app # refresh Finder icon cache
