#!/bin/sh
# ponytail: machine-local paths (JDK 17 + Android SDK on this Mac); upgrade = read from env when set.
set -e
export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME=/Users/ankur/Library/Android/sdk
cd "$(dirname "$0")"
# No args -> full gate (assembleDebug + unit tests); otherwise pass tasks/flags straight to gradlew.
[ $# -eq 0 ] && set -- assembleDebug testDebugUnitTest
exec ./gradlew "$@"
