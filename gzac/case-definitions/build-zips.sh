#!/bin/sh
# Rebuilds the importable GZAC zips from the source trees: the case-definition
# zips here plus the building-block zips in ../bouwblokken. Case zips bundle
# the building blocks their building-block-links reference, so one import per
# case zip suffices (GZAC imports bundled building blocks first and skips any
# that already exist). The zip entries keep the config/... layout that the
# Valtimo import service normalizes before matching its importers. Same
# output as `./gradlew buildGzacZips`.
set -e
cd "$(dirname "$0")"

bouwblokken_for() {
    case "$1" in
        inwonerplan) echo "pdca-actie jobcoaching-aanvragen" ;;
        binnenhof-renovatie) echo "pdca-actie" ;;
        *) echo "" ;;
    esac
}

for CASE in inwonerplan binnenhof-renovatie intake-werk-participatie renovatie-intake werkfit-aanvraag; do
    rm -f "$CASE.zip"
    (cd "$CASE" && zip -r -X "../$CASE.zip" config -x "*.DS_Store")
    for BOUWBLOK in $(bouwblokken_for "$CASE"); do
        (cd "../bouwblokken/$BOUWBLOK" && zip -r -X "../../case-definitions/$CASE.zip" config -x "*.DS_Store")
    done
    echo "built $CASE.zip"
done

for BOUWBLOK in pdca-actie jobcoaching-aanvragen; do
    rm -f "../bouwblokken/$BOUWBLOK.zip"
    (cd "../bouwblokken/$BOUWBLOK" && zip -r -X "../$BOUWBLOK.zip" config -x "*.DS_Store")
    echo "built bouwblokken/$BOUWBLOK.zip"
done
