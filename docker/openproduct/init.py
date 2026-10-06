# Idempotent dev provisioning for the local Open Product container.
# Executed via `manage.py shell -c "exec(open('/init/init.py').read())"` from the
# openproduct-init service in docker-compose.yml, after migrate + createinitialsuperuser.
#
# 1. Creates a fixed DRF token for the admin superuser (must match
#    `pdca.openproduct.token` in src/main/resources/application.yml).
# 2. Seeds the producttypen catalog from reference_data.py (also runnable on
#    its own on environments that keep their own tokens).
from django.contrib.auth import get_user_model
from rest_framework.authtoken.models import Token

TOKEN_KEY = "pdca-openproduct-dev-token-0123456789abc"

user = get_user_model().objects.get(username="admin")
Token.objects.filter(user=user).exclude(key=TOKEN_KEY).delete()
_, created = Token.objects.get_or_create(key=TOKEN_KEY, defaults={"user": user})
print(f"[openproduct-init] API token {'created' if created else 'already present'} for user 'admin'")

exec(open("/init/reference_data.py").read())
