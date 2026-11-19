#!/bin/sh
# Rebuilds the importable GZAC system process zips from the source trees. The
# zip entries keep the config/global/... layout that the process import
# expects. Same output as `./gradlew buildGzacZips`.
set -e
cd "$(dirname "$0")"

for PROCESS in pdca-document; do
    rm -f "$PROCESS.zip"
    (cd "$PROCESS" && zip -r -X "../$PROCESS.zip" config -x "*.DS_Store")
    echo "built $PROCESS.zip"
done
