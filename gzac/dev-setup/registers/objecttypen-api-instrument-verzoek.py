# Creates the objecttype InstrumentVerzoek (fixed uuid, published version 1) in
# the Objecttypen API of the local GZAC stack. Idempotent; a changed schema
# updates version 1. Run through setup-registers.sh, which fills in the schema
# from gzac/objecttypen/instrument-verzoek.schema.json.
import datetime
import json

from objecttypes.core.models import ObjectType, ObjectVersion

OBJECTTYPE_UUID = "00000000-0000-4000-8000-0000dca00001"
SCHEMA = json.loads(r'''__SCHEMA__''')
today = datetime.date.today()

objecttype, created = ObjectType.objects.get_or_create(
    uuid=OBJECTTYPE_UUID,
    defaults=dict(
        name="InstrumentVerzoek",
        name_plural="InstrumentVerzoeken",
        description="Verzoek om een instrument voor een doel van een PDCA-plan",
        data_classification="open",
        maintainer_organization="Ritense",
        contact_person="PDCA",
        update_frequency="unknown",
        labels={},
        created_at=today,
        modified_at=today,
    ),
)
version = ObjectVersion.objects.filter(object_type=objecttype, version=1).first()
if version is None:
    ObjectVersion.objects.create(
        object_type=objecttype, version=1, created_at=today, modified_at=today,
        published_at=today, json_schema=SCHEMA, status="published",
    )
    print(f"[objecttypen-api] objecttype InstrumentVerzoek {'created' if created else 'present'}, version 1 published")
elif version.json_schema != SCHEMA:
    version.json_schema = SCHEMA
    version.modified_at = today
    version.save()
    print("[objecttypen-api] objecttype InstrumentVerzoek present, version 1 schema updated")
else:
    print("[objecttypen-api] objecttype InstrumentVerzoek present, version 1 up to date")
