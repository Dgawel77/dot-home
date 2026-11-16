#!/usr/bin/env bash
# JVM unit tests for core/ (pure Java, no Android). Run on the NAS: tools/nas ./test.sh
set -euo pipefail
TOOLS=${TOOLS:-$HOME/androidtools}
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH

CORE=app/src/main/java/dev/gawel/dothome/core
# real framework references only (package-name string literals like "com.google.android.gm" are fine)
if grep -rnE 'import android\.|[^.a-z]android\.(os|app|content|graphics|util|view|widget|media|net|text|provider)\.' "$CORE"; then
  echo "core/ must not reference android.*" >&2
  exit 1
fi

OUT=build/test && rm -rf $OUT && mkdir -p $OUT
javac -encoding UTF-8 -Xlint:all,-options -source 8 -target 8 -d $OUT $(find $CORE tests -name '*.java')
java -cp $OUT dev.gawel.dothome.test.AllTests
