#!/bin/sh
# Rebuilds the importable GZAC case-definition zips from the source trees. The
# zip entries keep the config/... layout that the Valtimo import service
# normalizes before matching its importers. Same output as
# `./gradlew buildGzacZips`.
set -e
cd "$(dirname "$0")"

for CASE in inwonerplan binnenhof-renovatie intake-werk-participatie renovatie-intake instrument-verzoek; do
    rm -f "$CASE.zip"
    (cd "$CASE" && zip -r -X "../$CASE.zip" config -x "*.DS_Store")
    echo "built $CASE.zip"
done
