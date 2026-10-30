# The producttypen catalog (themas, organisaties, producttypen) that the PDCA
# app offers as instruments. The inwoner (W&P) producten carry the five W&P
# portfolio themes (Perspectief, Randvoorwaarden, Persoonlijke vermogens,
# Kansen, Werkgeversdienstverlening); the object-domain producten keep their
# own themes. Idempotent.
#
# Every producttype also gets the actie "StartInstrument": that is what makes
# it requestable from a plan (ARCHITECTURE.md §9). Its mapping is the start
# recipe the PDCA app reads: soort "verzoek" and the objecttype of the
# verzoek in the Objecten API. That objecttype URL is environment-specific
# (the Objecttypen API of the GZAC stack), so it comes from the environment
# variable PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE; without it the actie is
# created without objecttype and the app refuses the request with a message
# that says so — set the URL on the actie in the Open Product admin, or run
# this script again with the variable set. The parameter "startStatus" is the
# status the instrument starts with in the plan (actief or gepland).
#
# Runs in a Django shell of the Open Product container. Locally init.py
# executes it after provisioning the dev token; on any other environment run
# it on its own, so the existing API tokens stay untouched:
#
#   PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE=https://.../api/v2/objecttypes/<uuid> \
#     python src/manage.py shell < reference_data.py
import os
from datetime import date

from openproduct.locaties.models import Organisatie
from openproduct.producttypen.models import Actie, Parameter, ProductType, Thema

START_ACTIE = "StartInstrument"
VERZOEK_OBJECTTYPE = os.environ.get("PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE", "").strip()
# Where the verzoek lands; informational (the app reaches the Objecten API
# through its GZAC contract), but an actie needs a url or a DMN table.
OBJECTEN_URL = os.environ.get("PDCA_OBJECTEN_URL", "http://host.docker.internal:8010").rstrip("/")


def start_mapping():
    static = [{"name": "soort", "classType": "String", "value": "verzoek"}]
    if VERZOEK_OBJECTTYPE:
        static.append({"name": "type", "classType": "String", "value": VERZOEK_OBJECTTYPE})
        static.append({"name": "typeVersion", "classType": "Integer", "value": "1"})
    return {"static": static}


def ensure_start_actie(pt):
    actie = Actie.objects.filter(producttype=pt, naam=START_ACTIE).first()
    if actie is None:
        Actie.objects.create(
            producttype=pt, naam=START_ACTIE, direct_url=f"{OBJECTEN_URL}/api/v2/objects", mapping=start_mapping()
        )
        return "created"
    statics = {s.get("name"): s.get("value") for s in (actie.mapping or {}).get("static", [])}
    if VERZOEK_OBJECTTYPE and statics.get("type") != VERZOEK_OBJECTTYPE:
        actie.mapping = start_mapping()
        actie.save()
        return "objecttype updated"
    return "present"


def ensure_parameter(pt, naam, waarde):
    Parameter.objects.get_or_create(producttype=pt, naam=naam, defaults={"waarde": waarde})


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
        "thema": "Persoonlijke vermogens",
        "organisatie": ("WERKSE", "Werkse!", "Delft"),
        "keywords": ["werkzoekenden", "wio-doelgroep"],
        "duur": "6 maanden",
    },
    {
        "code": "JOBCOACHING",
        "naam": "Jobcoaching",
        "samenvatting": "Individuele begeleiding op de werkplek door een gecertificeerde jobcoach",
        "doelgroep": "burgers",
        "thema": "Kansen",
        "organisatie": ("RANDSTAD", "Randstad", "Diemen"),
        "keywords": ["arbeidsbeperking", "werkenden met ondersteuningsbehoefte"],
        "duur": "12 maanden",
    },
    {
        "code": "LEERBAARHEIDSTOETS",
        "naam": "Leerbaarheidstoets",
        "samenvatting": "Assessment om leervermogen en ontwikkelmogelijkheden in kaart te brengen",
        "doelgroep": "burgers",
        "thema": "Perspectief",
        "organisatie": ("ROC-MONDRIAAN", "ROC Mondriaan", "Den Haag"),
        "keywords": ["jongeren", "herintreders"],
        "duur": "2 weken",
    },
    {
        "code": "ORIENTEREND-AANBOD",
        "naam": "Orienterend Aanbod",
        "samenvatting": "Korte orientatieperiode om werkinteresses en mogelijkheden te verkennen",
        "doelgroep": "burgers",
        "thema": "Perspectief",
        "organisatie": ("GEMEENTE-DEN-HAAG", "Gemeente Den Haag", "Den Haag"),
        "keywords": ["werkzoekenden", "statushouders"],
        "duur": "3 maanden",
    },
    {
        "code": "NAZORGTRAJECT",
        "naam": "Nazorgtraject",
        "samenvatting": "Nazorg en monitoring na succesvolle plaatsing op een werkplek",
        "doelgroep": "burgers",
        "thema": "Werkgeversdienstverlening",
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
        "thema": "Randvoorwaarden",
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
        print(f"[openproduct-reference-data] Producttype {spec['code']} created")
    else:
        print(f"[openproduct-reference-data] Producttype {spec['code']} already present")
    ensure_parameter(pt, "startStatus", "actief")
    print(f"[openproduct-reference-data] Producttype {spec['code']}: actie {START_ACTIE} {ensure_start_actie(pt)}")

if not VERZOEK_OBJECTTYPE:
    print(
        "[openproduct-reference-data] PDCA_INSTRUMENT_VERZOEK_OBJECTTYPE is not set: the StartInstrument acties "
        "carry no objecttype yet; set it on the acties in the Open Product admin or rerun with the variable."
    )
print(f"[openproduct-reference-data] Done. {ProductType.objects.count()} producttypen in register.")
