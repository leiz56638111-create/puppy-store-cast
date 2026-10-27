#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or 21}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
python3 airplay/tools/prepare-sources.py
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon :airplay:assembleDebug :airplay:testDebugUnitTest :airplay:lintDebug
