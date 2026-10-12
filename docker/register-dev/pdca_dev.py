# Local-dev settings for the Open Plan, Open Product and Open Klant containers
# in docker-compose.yml. Mounted as `<register>/conf/pdca_dev.py` and selected
# with DJANGO_SETTINGS_MODULE=<register>.conf.pdca_dev; it extends the
# register's own `docker` settings next to it.
#
# On top of those settings it:
# 1. gives every register its own CSRF cookie. The registers all run on
#    localhost and browsers share cookies across ports, so the default
#    `csrftoken` of one admin would invalidate the open forms of another.
# 2. honours DISABLE_2FA, which Open Plan's `docker` settings ignore (only
#    its `dev` settings read it), so its admin does not demand a 2FA setup.
# 3. with PDCA_ADMIN_AUTOLOGIN=true, logs every visitor of /admin/ in as the
#    dev superuser `admin`, so the admin pages open logged in.
import importlib
import os

_base = importlib.import_module(f"{__package__}.docker")
globals().update({name: value for name, value in vars(_base).items() if name.isupper()})

CSRF_COOKIE_NAME = SESSION_COOKIE_NAME.replace("sessionid", "csrftoken")  # noqa: F821

if os.environ.get("DISABLE_2FA", "").lower() == "true":
    MAYKIN_2FA_ALLOW_MFA_BYPASS_BACKENDS = AUTHENTICATION_BACKENDS  # noqa: F821

if os.environ.get("PDCA_ADMIN_AUTOLOGIN") == "true":
    _auth = MIDDLEWARE.index("django.contrib.auth.middleware.AuthenticationMiddleware")  # noqa: F821
    MIDDLEWARE = [  # noqa: F821
        *MIDDLEWARE[: _auth + 1],
        f"{__name__}.AdminAutoLoginMiddleware",
        *MIDDLEWARE[_auth + 1 :],
    ]


class AdminAutoLoginMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if request.path.startswith("/admin/") and not request.user.is_authenticated:
            from django.contrib.auth import get_user_model, login

            admin = get_user_model().objects.filter(username="admin", is_active=True).first()
            if admin is not None:
                login(request, admin, backend="django.contrib.auth.backends.ModelBackend")
        return self.get_response(request)
