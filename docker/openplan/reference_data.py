# Reference data the PDCA app uses directly through the Open Plan API:
# plantypen (= dienstverleningen), named hoofd-/subdoel types (the doeltype
# register; FIXED uuids so environments stay comparable), instrumenttypen,
# relatietypen and the overkoepelend plan. Idempotent.
#
# Runs in a Django shell of the Open Plan container. Locally init.py executes
# it after provisioning the dev token; on any other environment run it on its
# own, so the existing API tokens stay untouched:
#
#   python src/manage.py shell < reference_data.py
from uuid import UUID

from openplan.plannen.models.doelcategorie import DoelCategorie
from openplan.plannen.models.doeltype import DoelType
from openplan.plannen.models.instrumenttype import InstrumentType
from openplan.plannen.models.overkoepelendplan import OverkoepelendPlan
from openplan.plannen.models.plantype import PlanType
from openplan.plannen.models.relatietype import RelatieType

for t in ("werk", "pip", "inkomen"):
    PlanType.objects.get_or_create(type=t)

# Doeltype categories carry two things. The marker "Hoofddoel" separates
# hoofddoel types (a plan has exactly one) from subdoel types. Every other
# category is a THEME and scopes the product catalog: under a subdoel the
# plan page only offers producttypen from Open Product carrying one of those
# themes. The inwonerdomein uses the five W&P portfolio themes, the
# objectdomein its own; the names match docker/openproduct/reference_data.py.
HOOFDDOEL_CATEGORIE = "Hoofddoel"


def categorie(naam):
    obj, _ = DoelCategorie.objects.get_or_create(naam=naam)
    return obj


def doeltype(uid, naam, categorieen):
    obj, dt_created = DoelType.objects.get_or_create(uuid=UUID(uid), defaults={"doel_type": naam})
    if not dt_created and obj.doel_type != naam:
        obj.doel_type = naam
        obj.save()
    obj = DoelType.objects.get(uuid=UUID(uid))
    for naam_categorie in categorieen:
        if not obj.categorieen.filter(naam=naam_categorie).exists():
            obj.categorieen.add(categorie(naam_categorie))


# Fixed, defined hoofd- and subdoelen: doelen are chosen
# from this register, no free-text doelen. DoelType.doel_type carries the
# doel's name. The intake forms of the bundled case types reference these
# doelen by NAME.
HOOFDDOELTYPEN = [
    # inwonerdomein
    ("88888888-8888-8888-8888-888888888801", "Duurzaam aan het werk"),
    ("88888888-8888-8888-8888-888888888802", "Financieel zelfredzaam"),
    ("88888888-8888-8888-8888-888888888803", "Zelfstandig meedoen in de samenleving"),
    # objectdomein
    ("88888888-8888-8888-8888-888888888811", "Object structureel veilig in gebruik"),
]
# (uuid, naam, themes) — the themes scope the producttypen offered under the subdoel.
SUBDOELTYPEN = [
    # inwonerdomein: the five W&P portfolio themes
    ("88888888-8888-8888-8888-888888889901", "Financiele situatie in kaart brengen", ("Randvoorwaarden",)),
    ("88888888-8888-8888-8888-888888889902", "Competenties en werkervaring beoordelen", ("Perspectief",)),
    ("88888888-8888-8888-8888-888888889903", "Werkfit vaardigheden ontwikkelen", ("Persoonlijke vermogens",)),
    ("88888888-8888-8888-8888-888888889904", "Kinderopvang regelen", ("Randvoorwaarden",)),
    ("88888888-8888-8888-8888-888888889905", "Orientatie op passende functies", ("Perspectief",)),
    ("88888888-8888-8888-8888-888888889906", "Duurzame plaatsing realiseren", ("Kansen",)),
    ("88888888-8888-8888-8888-888888889907", "Nazorg en borging", ("Werkgeversdienstverlening",)),
    ("88888888-8888-8888-8888-888888889908", "Taalniveau verbeteren", ("Persoonlijke vermogens",)),
    ("88888888-8888-8888-8888-888888889909", "Schulden stabiliseren", ("Randvoorwaarden",)),
    # objectdomein
    ("88888888-8888-8888-8888-888888889911", "Brandveiligheidsrisico's inventariseren", ("Analyse & Advies",)),
    ("88888888-8888-8888-8888-888888889912", "Geconstateerde gebreken verhelpen", ("Training & Educatie",)),
    ("88888888-8888-8888-8888-888888889913", "Herinspectie uitvoeren", ("Inspectie & Controle",)),
    ("88888888-8888-8888-8888-888888889914", "Borging in beheerorganisatie", ("Training & Educatie", "Inspectie & Controle")),
]
for uid, naam in HOOFDDOELTYPEN:
    doeltype(uid, naam, (HOOFDDOEL_CATEGORIE,))
for uid, naam, themas in SUBDOELTYPEN:
    doeltype(uid, naam, themas)

for t in ("training", "coaching", "financiele_ondersteuning"):
    InstrumentType.objects.get_or_create(instrument_type=t)

for naam in (
    "Procesbegeleider", "Regiebehandelaar", "Arbeidscoach", "Schuldhulpverlener",
    "Inwoner / Eigenaar", "Projectleider", "Brandveiligheidsadviseur",
    "Gebouwbeheerder", "Inspecteur", "Aanbieder", "Coach",
):
    RelatieType.objects.get_or_create(naam=naam)

OverkoepelendPlan.objects.get_or_create(
    uuid=UUID("10000000-0000-0000-0000-000000000001"),
    defaults={"titel": "PDCA Prototype", "status": "actief"},
)

print(f"[openplan-reference-data] Reference data present ({DoelType.objects.count()} doeltypen, "
      f"{RelatieType.objects.count()} relatietypen, {PlanType.objects.count()} plantypen)")
