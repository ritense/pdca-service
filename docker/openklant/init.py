# Idempotent dev provisioning for the local Open Klant container.
# Executed via `manage.py shell -c "exec(open('/init/init.py').read())"` from the
# openklant-init service in docker-compose.yml, after migrate.
#
# 1. Creates the `admin`/`admin` superuser (Open Klant's createinitialsuperuser
#    generates and mails a password instead of taking one).
# 2. Creates an API token with a fixed key (must match `pdca.openklant.token`
#    in src/main/resources/application.yml). Open Klant does not use DRF
#    tokens but its own TokenAuth model; the header is the same
#    `Authorization: Token <key>`.
from django.contrib.auth import get_user_model

from openklant.components.token.models import TokenAuth

TOKEN_KEY = "pdca-openklant-dev-token-0123456789abcde"

User = get_user_model()
if not User.objects.filter(username="admin").exists():
    User.objects.create_superuser("admin", "admin@example.com", "admin")
    print("[openklant-init] superuser 'admin' created")

_, created = TokenAuth.objects.update_or_create(
    identifier="pdca-app",
    defaults={
        "token": TOKEN_KEY,
        "contact_person": "PDCA app",
        "email": "admin@example.com",
        "application": "PDCA app",
    },
)
print(f"[openklant-init] API token {'created' if created else 'already present'} for 'pdca-app'")
