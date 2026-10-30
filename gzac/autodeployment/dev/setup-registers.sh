#!/bin/sh
# One-time setup of the registers of the local GZAC stack (gzac-docker-compose)
# for the verzoek route: the objecttype InstrumentVerzoek in the Objecttypen
# API and its registration plus token permission in the Objecten API. Both
# steps are idempotent. Prints the objecttype URL the Open Product acties need.
set -e
cd "$(dirname "$0")"

python3 - <<'PY' | docker exec -i gzac-docker-compose-objecttypen-api python /app/src/manage.py shell
import pathlib
schema = pathlib.Path("../../objecttypen/instrument-verzoek.schema.json").read_text()
print(pathlib.Path("objecttypen-api-instrument-verzoek.py").read_text().replace("__SCHEMA__", schema))
PY

docker exec -i gzac-docker-compose-objecten-api python /app/src/manage.py shell < objecten-api-instrument-verzoek.py

echo "Objecttype URL for the StartInstrument acties (PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE):"
echo "  http://host.docker.internal:8011/api/v2/objecttypes/00000000-0000-4000-8000-0000dca00001"
