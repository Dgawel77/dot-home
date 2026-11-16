#!/usr/bin/env bash
# Builds iconpack.apk from exported icons. Run on the NAS: tools/nas tools/make_iconpack.sh
# Input: $ICONS_DIR (default build/icons) holding <drawable>.png files and index.tsv
#        (component<TAB>drawable<TAB>mono|auto). Hand-made iconpack/overrides/<drawable>.png win.
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS=${TOOLS:-$HOME/androidtools}
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk}
export PATH=$JAVA_HOME/bin:$PATH
IN=${ICONS_DIR:-build/icons}
[ -f "$IN/index.tsv" ] || { echo "missing $IN/index.tsv" >&2; exit 1; }

RES=iconpack/res
rm -rf $RES && mkdir -p $RES/drawable-nodpi $RES/xml
# Only PNGs listed in index.tsv are packaged; stale files from earlier exports are ignored.
while IFS=$'\t' read -r comp name kind; do
  [ -n "$name" ] || continue
  [ -f "$IN/$name.png" ] || { echo "missing $IN/$name.png for $comp" >&2; exit 1; }
  cp "$IN/$name.png" $RES/drawable-nodpi/
done < "$IN/index.tsv"
if [ -d iconpack/overrides ]; then
  for f in iconpack/overrides/*.png; do
    [ -e "$f" ] || continue
    if cut -f2 "$IN/index.tsv" | grep -qxF "$(basename "$f" .png)"; then
      cp "$f" $RES/drawable-nodpi/
    else
      echo "ignoring override $f: not in index.tsv" >&2
    fi
  done
fi

# Reuse the core AppFilter (same naming as the exporter) instead of duplicating the XML logic.
CLS=build/appfilter-tool && rm -rf $CLS && mkdir -p $CLS
javac -encoding UTF-8 -Xlint:-options -source 8 -target 8 -d $CLS \
  app/src/main/java/dev/gawel/dothome/core/AppFilter.java tools/AppFilterMain.java
java -cp $CLS AppFilterMain "$IN/index.tsv" $RES/drawable-nodpi $RES/xml/appfilter.xml

iconpack/build.sh
