#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?The GitHub runner must provide an Android SDK}"
: "${ANDROID_PLATFORM:?Missing platform package}"
: "${ANDROID_BUILD_TOOLS:?Missing build-tools version}"

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
test -x "$sdkmanager"
# yes may receive SIGPIPE once sdkmanager has consumed all license prompts.
set +o pipefail
yes | "$sdkmanager" --licenses > /dev/null
license_status=${PIPESTATUS[1]}
set -o pipefail
if [[ "$license_status" != 0 ]]; then exit "$license_status"; fi
"$sdkmanager" "$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS" "platform-tools"
