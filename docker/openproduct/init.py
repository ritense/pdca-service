# Idempotent dev provisioning for the local Open Product container.
# Executed via `manage.py shell -c "exec(open('/init/init.py').read())"` from the
# openproduct-init service in docker-compose.yml, after migrate + createinitialsuperuser.
#
# 1. Creates a fixed DRF token for the admin superuser (must match
#    `pdca.openproduct.token` in src/main/resources/application.yml).
# 2. Seeds the producttypen catalog (themas, organisaties, producttypen) that the
#    PDCA app offers as instruments. Replaces the old in-memory MockProductRegister.
from datetime import date

from django.contrib.auth import get_user_model
from rest_framework.authtoken.models import Token

from openproduct.locaties.models import Organisatie
from openproduct.producttypen.models import Parameter, ProductType, Thema

TOKEN_KEY = "pdca-openproduct-dev-token-0123456789abc"

user = get_user_model().objects.get(username="admin")
Token.objects.filter(user=user).exclude(key=TOKEN_KEY).delete()
_, created = Token.objects.get_or_create(key=TOKEN_KEY, defaults={"user": user})
print(f"[openproduct-init] API token {'created' if created else 'already present'} for user 'admin'")


def thema(naam):
    obj = Thema.objects.filter(naam=naam, hoofd_thema=None).first()
    if obj is None:
        obj = Thema.objects.create(naam=naam, hoofd_thema=None, gepubliceerd=True)
    return obj


def organisatie(code, naam, stad=""):
    obj, _ = Organisatie.objects.get_or_create(code=code, defaults={"naam": naam, "stad": stad})
    return obj


PRODUCTTYPEN = [
    {
        "code": "WERKFIT-TRAJECT",
        "naam": "Werkfit Traject WIO",
        "samenvatting": "Traject gericht op het vergroten van werkfit-vaardigheden binnen de WIO-doelgroep",
        "doelgroep": "burgers",
        "thema": "Werk & Dagbesteding",
        "organisatie": ("WERKSE", "Werkse!", "Delft"),
        "keywords": ["werkzoekenden", "wio-doelgroep"],
        "duur": "6 maanden",
    },
    {
        "code": "JOBCOACHING",
        "naam": "Jobcoaching",
        "samenvatting": "Individuele begeleiding op de werkplek door een gecertificeerde jobcoach",
        "doelgroep": "burgers",
        "thema": "Werk & Dagbesteding",
        "organisatie": ("RANDSTAD", "Randstad", "Diemen"),
        "keywords": ["arbeidsbeperking", "werkenden met ondersteuningsbehoefte"],
        "duur": "12 maanden",
    },
    {
        "code": "LEERBAARHEIDSTOETS",
        "naam": "Leerbaarheidstoets",
        "samenvatting": "Assessment om leervermogen en ontwikkelmogelijkheden in kaart te brengen",
        "doelgroep": "burgers",
        "thema": "Intake & Assessment",
        "organisatie": ("ROC-MONDRIAAN", "ROC Mondriaan", "Den Haag"),
        "keywords": ["jongeren", "herintreders"],
        "duur": "2 weken",
    },
    {
        "code": "ORIENTEREND-AANBOD",
        "naam": "Orienterend Aanbod",
        "samenvatting": "Korte orientatieperiode om werkinteresses en mogelijkheden te verkennen",
        "doelgroep": "burgers",
        "thema": "Werk & Dagbesteding",
        "organisatie": ("GEMEENTE-DEN-HAAG", "Gemeente Den Haag", "Den Haag"),
        "keywords": ["werkzoekenden", "statushouders"],
        "duur": "3 maanden",
    },
    {
        "code": "NAZORGTRAJECT",
        "naam": "Nazorgtraject",
        "samenvatting": "Nazorg en monitoring na succesvolle plaatsing op een werkplek",
        "doelgroep": "burgers",
        "thema": "Werk & Dagbesteding",
        "organisatie": ("GEMEENTE-DEN-HAAG", "Gemeente Den Haag", "Den Haag"),
        "keywords": ["nazorg", "recent geplaatste werknemers"],
        "duur": "6 maanden",
    },
    {
        "code": "BRANDVEILIGHEIDSONDERZOEK",
        "naam": "Brandveiligheidsonderzoek",
        "samenvatting": "Onderzoek naar brandveiligheid van gebouwen en objecten",
        "doelgroep": "bedrijven_en_instellingen",
        "thema": "Analyse & Advies",
        "organisatie": ("BRANDWEER-NL", "Brandweer NL", "Arnhem"),
        "keywords": ["gebouweigenaren", "monumentenbeheerders"],
        "duur": "4 weken",
    },
    {
        "code": "BHV-TRAINING",
        "naam": "BHV-training",
        "samenvatting": "Bedrijfshulpverleningstraining conform wettelijke eisen",
        "doelgroep": "bedrijven_en_instellingen",
        "thema": "Training & Educatie",
        "organisatie": ("VEILIGHEIDSREGIO", "Veiligheidsregio", "Den Haag"),
        "keywords": ["bhv-ers", "medewerkers"],
        "duur": "2 dagen",
    },
    {
        "code": "INSPECTIEDIENST",
        "naam": "Inspectiedienst",
        "samenvatting": "Technische inspectie en controle van installaties en constructies",
        "doelgroep": "bedrijven_en_instellingen",
        "thema": "Inspectie & Controle",
        "organisatie": ("TUV-NEDERLAND", "TUV Nederland", "Best"),
        "keywords": ["gebouweigenaren", "installatiebeheerders"],
        "duur": "1 week",
    },
    {
        "code": "FINANCIELE-INTAKE",
        "naam": "Financiele Intake",
        "samenvatting": "Inventarisatie van de financiele situatie en mogelijkheden voor ondersteuning",
        "doelgroep": "burgers",
        "thema": "Intake & Assessment",
        "organisatie": ("GEMEENTE-DEN-HAAG", "Gemeente Den Haag", "Den Haag"),
        "keywords": ["bijstandsgerechtigden", "minima"],
        "duur": "2 weken",
    },
]

for spec in PRODUCTTYPEN:
    pt = ProductType.objects.filter(code=spec["code"]).first()
    if pt is None:
        # ProductType.gepubliceerd is derived from the publication window.
        pt = ProductType(
            code=spec["code"],
            doelgroep=spec["doelgroep"],
            publicatie_start_datum=date.today(),
            keywords=spec["keywords"],
        )
        pt.set_current_language("nl")
        pt.naam = spec["naam"]
        pt.samenvatting = spec["samenvatting"]
        pt.save()
        pt.themas.add(thema(spec["thema"]))
        pt.organisaties.add(organisatie(*spec["organisatie"]))
        Parameter.objects.create(producttype=pt, naam="duur", waarde=spec["duur"])
        print(f"[openproduct-init] Producttype {spec['code']} created")
    else:
        print(f"[openproduct-init] Producttype {spec['code']} already present")

print(f"[openproduct-init] Done. {ProductType.objects.count()} producttypen in register.")
