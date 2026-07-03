#!/bin/bash
# Lokale WSL-SDK-Pfade nur setzen, wenn sie existieren (z.B. in CI bringt
# das chip-build-android-Image sein eigenes, bereits korrekt gesetztes SDK mit)
if [ -d "/home/ricardo/Android/Sdk" ]; then
    export ANDROID_HOME=/home/ricardo/Android/Sdk
    export ANDROID_NDK_HOME=/home/ricardo/Android/Sdk/ndk/28.2.13676358
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    export PATH="$PATH:/home/ricardo/kotlinc/bin"
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT/matter-sdk"
source scripts/activate.sh
./scripts/build/build_examples.py --target android-arm64-chip-tool build
