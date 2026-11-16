#!/usr/bin/env bash
# JVM unit tests for core/ (pure Java, no Android). Run on the NAS: tools/nas ./test.sh
set -euo pipefail
TOOLS=${TOOLS:-$HOME/androidtools}
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH

CORE=app/src/main/java/dev/gawel/dothome/core
if grep -rn 'import android' "$CORE"; then
  echo "core/ must not import android.*" >&2
  exit 1
fi

OUT=build/test && rm -rf $OUT && mkdir -p $OUT
javac -Xlint:all,-options -source 8 -target 8 -d $OUT $(find $CORE tests -name '*.java')
java -cp $OUT dev.gawel.dothome.test.AllTests
