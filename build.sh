#!/usr/bin/env bash
# Builds app-release.apk without Gradle (JDK + Android SDK under ~/androidtools; run on the NAS).
# MODULE/PKG/APK pick another module, e.g. `MODULE=torch PKG=dev.gawel.dottorch APK=torch-release.apk ./build.sh`.
set -euo pipefail
TOOLS=${TOOLS:-$HOME/androidtools}
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH
SDK=$TOOLS/sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar

MODULE=${MODULE:-app}
PKG=${PKG:-dev.gawel.dothome}
APK=${APK:-app-release.apk}
OUT=build/apk-$MODULE && rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex
M=$MODULE/src/main
sed "s#<manifest #<manifest package=\"$PKG\" #" $M/AndroidManifest.xml > $OUT/AndroidManifest.xml
"$BT/aapt2" compile --dir $M/res -o $OUT/res.zip
ASSETS=()
[ -d $M/assets ] && ASSETS=(-A $M/assets)
"$BT/aapt2" link -o $OUT/base.apk -I "$JAR" --manifest $OUT/AndroidManifest.xml \
  --java $OUT/gen --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 0.1 \
  "${ASSETS[@]}" $OUT/res.zip
javac -encoding UTF-8 -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d $OUT/classes $(find $M/java $OUT/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output $OUT/dex $(find $OUT/classes -name '*.class')
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 $OUT/unsigned.apk $OUT/aligned.apk

KS=${KEYSTORE:-$HOME/.android/dothome.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias dothome \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dot Home"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$APK" $OUT/aligned.apk
echo "Built $(pwd)/$APK"
