#!/usr/bin/env bash
# Android CLI toolchain, user-local, no sudo.
set -euo pipefail
OPT="$HOME/.local/opt"
SDK="$HOME/Android/Sdk"
DL="${TMPDIR:-/tmp}/android-dl"
mkdir -p "$OPT" "$SDK/cmdline-tools" "$DL" "$HOME/.local/bin"

echo "[1/4] JDK 17 (Temurin)"
if [ ! -x "$OPT/jdk-17/bin/javac" ]; then
  curl -fL --retry 3 -o "$DL/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf "$OPT"/jdk-17*; tar -xzf "$DL/jdk17.tar.gz" -C "$OPT"
  mv "$OPT"/jdk-17.* "$OPT/jdk-17"
fi
export JAVA_HOME="$OPT/jdk-17"; export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "[2/4] Android cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 3 -o "$DL/cmdline.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
  rm -rf "$DL/cmdline-tools"; unzip -q "$DL/cmdline.zip" -d "$DL"
  rm -rf "$SDK/cmdline-tools/latest"; mv "$DL/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
AND="$SDK/cmdline-tools/latest/bin/android"

echo "[3/4] SDK packages"
# cmdline-tools 23+ replaced sdkmanager with `android sdk`; package ids use '/', licenses
# no longer need accepting. (Old `yes | sdkmanager --licenses` + pipefail killed this script.)
"$AND" --sdk="$SDK" sdk install platforms/android-36 build-tools/36.1.0 platform-tools

echo "[4/4] Gradle 9.8.2"
if [ ! -x "$OPT/gradle-9.8.2/bin/gradle" ]; then
  curl -fL --retry 3 -o "$DL/gradle.zip" "https://services.gradle.org/distributions/gradle-9.8.2-bin.zip"
  unzip -q "$DL/gradle.zip" -d "$OPT"
fi
ln -sf "$OPT/gradle-9.8.2/bin/gradle" "$HOME/.local/bin/gradle"

rm -rf "$DL"
du -sh "$OPT/jdk-17" "$SDK" "$OPT/gradle-9.8.2"
echo DONE
