#!/bin/sh
# Rebuilds the importable GZAC case-definition zips from the source trees.
# The zip entries keep the config/case/<key>/<version>/ layout that the
# Valtimo import service normalizes before matching its importers.
set -e
cd "$(dirname "$0")"

for CASE in inwonerplan binnenhof-renovatie; do
    rm -f "$CASE.zip"
    (cd "$CASE" && zip -r -X "../$CASE.zip" config -x "*.DS_Store")
    echo "built $CASE.zip"
done
