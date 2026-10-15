#!/bin/sh
# Builds the release APK and signs it with the owner's release key when present.
set -eu
cd "$(dirname "$0")"
./gradlew :app:assembleRelease
mkdir -p dist
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
if [ -f "$HOME/.android-certs/releasekey.pk8" ]; then
    bt="$(find "$HOME/Android/Sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
    "$bt/apksigner" sign --key "$HOME/.android-certs/releasekey.pk8" \
        --cert "$HOME/.android-certs/releasekey.x509.pem" --out dist/yao.apk "$unsigned"
    echo "dist/yao.apk"
else
    cp "$unsigned" dist/yao-unsigned.apk
    echo "dist/yao-unsigned.apk (no release key here)"
fi
