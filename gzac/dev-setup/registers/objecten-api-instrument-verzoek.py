# Registers the objecttype InstrumentVerzoek in the Objecten API of the local
# GZAC stack and lets the stack's API token (used by GZAC and by the objecten
# plugin on the plugin host) read and write its objects. Idempotent.
from objects.core.models import ObjectType
from objects.token.models import Permission, TokenAuth
from zgw_consumers.models import Service

OBJECTTYPE_UUID = "00000000-0000-4000-8000-0000dca00001"
TOKENS = ["182c13e2209161852c53cef53a879f7a2f923430"]

service = Service.objects.filter(api_type="orc").order_by("id").first()
if service is None:
    raise SystemExit("[objecten-api] no Objecttypen API service configured")
objecttype, created = ObjectType.objects.get_or_create(
    service=service, uuid=OBJECTTYPE_UUID, defaults={"_name": "InstrumentVerzoek"}
)
print(f"[objecten-api] objecttype InstrumentVerzoek {'registered' if created else 'present'} under {service.api_root}")
for token in TokenAuth.objects.filter(token__in=TOKENS):
    _, granted = Permission.objects.get_or_create(
        token_auth=token, object_type=objecttype, defaults={"mode": "read_and_write"}
    )
    print(f"[objecten-api] token of '{token.contact_person}': read_and_write {'granted' if granted else 'present'}")
