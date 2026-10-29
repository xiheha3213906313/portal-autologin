#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
mkdir -p dist
if [ -f signing/module.p12 ] && [ -f signing/password ]; then
    cp app/build/outputs/apk/release/app-release.apk dist/portal-autologin-1.3.0.apk
    sha256sum dist/portal-autologin-1.3.0.apk
else
    cp app/build/outputs/apk/release/app-release-unsigned.apk dist/portal-autologin-1.3.0-unsigned.apk
    sha256sum dist/portal-autologin-1.3.0-unsigned.apk
fi
