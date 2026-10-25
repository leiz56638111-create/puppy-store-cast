#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
python3 mirror/tools/build-native-deps.py
mkdir -p signing
if [ ! -f signing/test.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore signing/test.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=LivingRoomCast Test,O=Local Development,C=CN'
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon :direct:assembleDebug :direct:lintDebug :direct:testDebugUnitTest
