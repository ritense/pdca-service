#!/bin/sh
# Rebuilds the importable GZAC building block zips from the source trees. The
# zip entries keep the config/building-block/... layout that the building
# block import expects. Same output as `./gradlew buildGzacZips`.
set -e
cd "$(dirname "$0")"

for BLOCK in pdca-taak; do
    rm -f "$BLOCK.zip"
    (cd "$BLOCK" && zip -r -X "../$BLOCK.zip" config -x "*.DS_Store")
    echo "built $BLOCK.zip"
done
