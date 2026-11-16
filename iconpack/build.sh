#!/usr/bin/env bash
# Builds iconpack.apk (resources only, no code) from iconpack/res; run on the NAS via tools/make_iconpack.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS=${TOOLS:-$HOME/androidtools}
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH
SDK=$TOOLS/sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar

OUT=build/iconpack && rm -rf $OUT && mkdir -p $OUT
"$BT/aapt2" compile --dir iconpack/res -o $OUT/res.zip
"$BT/aapt2" link -o $OUT/base.apk -I "$JAR" --manifest iconpack/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 1.0 $OUT/res.zip
"$BT/zipalign" -f 4 $OUT/base.apk $OUT/aligned.apk

KS=${KEYSTORE:-$HOME/.android/dothome.jks}
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out iconpack.apk $OUT/aligned.apk
echo "Built $(pwd)/iconpack.apk"
