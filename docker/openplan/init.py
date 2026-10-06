# Idempotent dev provisioning for the local Open Plan container.
# Executed via `manage.py shell -c "exec(open('/init/init.py').read())"` from the
# openplan-init service in docker-compose.yml, after migrate + createinitialsuperuser.
#
# Provisions:
#   1. a fixed DRF token for the admin superuser (must match `pdca.openplan.token`
#      in src/main/resources/application.yml);
#   2. the reference data in reference_data.py (also runnable on its own on
#      environments that keep their own tokens).
#
# Demo plans are NOT seeded anymore: they are created through the bundled
# intake case types (gzac/case-definitions/*). The intake form fills the
# dossier document with the full plan structure; the PDCA app's create-plan
# task form turns it into the plan (prefill mapping in the PDCA Beheer
# configuration).
from django.contrib.auth import get_user_model
from rest_framework.authtoken.models import Token

TOKEN_KEY = "pdca-openplan-dev-token-0123456789abcdef"

user = get_user_model().objects.get(username="admin")
# Token.user is a OneToOneField: drop any token with a different key first.
Token.objects.filter(user=user).exclude(key=TOKEN_KEY).delete()
_, created = Token.objects.get_or_create(key=TOKEN_KEY, defaults={"user": user})
print(f"[openplan-init] API token {'created' if created else 'present'} for user 'admin'")

exec(open("/init/reference_data.py").read())
