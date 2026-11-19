#!/bin/sh
# One-time setup of the registers of the local GZAC stack (gzac-docker-compose)
# for the verzoek route: the objecttype InstrumentVerzoek in the Objecttypen
# API and its registration plus token permission in the Objecten API. Both
# steps are idempotent. Prints the objecttype URL the Open Product acties need.
#
# The containers are found by their compose project and service
# (GZAC_COMPOSE_PROJECT, default gzac-docker-compose), not by container name.
set -e
cd "$(dirname "$0")"

project="${GZAC_COMPOSE_PROJECT:-gzac-docker-compose}"
container() {
  id=$(docker ps -q --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$1" | head -n 1)
  if [ -z "$id" ]; then
    echo "No running container for service '$1' of compose project '$project'" >&2
    exit 1
  fi
  echo "$id"
}
objecttypen=$(container objecttypen-api)
objecten=$(container objecten-api)

python3 - <<'PY' | docker exec -i "$objecttypen" python /app/src/manage.py shell
import pathlib
schema = pathlib.Path("../../objecttypen/instrument-verzoek.schema.json").read_text()
print(pathlib.Path("objecttypen-api-instrument-verzoek.py").read_text().replace("__SCHEMA__", schema))
PY

docker exec -i "$objecten" python /app/src/manage.py shell < objecten-api-instrument-verzoek.py

echo "Objecttype URL for the StartInstrument acties (PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE):"
echo "  http://host.docker.internal:8011/api/v2/objecttypes/00000000-0000-4000-8000-0000dca00001"
