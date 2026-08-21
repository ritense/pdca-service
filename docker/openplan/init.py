# Idempotent dev provisioning for the local Open Plan container.
# Executed via `manage.py shell -c "exec(open('/init/init.py').read())"` from the
# openplan-init service in docker-compose.yml, after migrate + createinitialsuperuser.
#
# Provisions:
#   1. a fixed DRF token for the admin superuser (must match `pdca.openplan.token`
#      in src/main/resources/application.yml);
#   2. reference data the PDCA app uses directly through the API: plantypen
#      (= dienstverleningen), doelcategorieën + benoemde hoofd-/subdoeltypen
#      (het doeltype-register; FIXED uuids omdat de overlay er per plan naar
#      verwijst), instrumenttypen, relatietypen;
#   3. demo plannen/doelen/instrumenten/contactmomenten/personen with FIXED
#      uuids, so the PDCA overlay seed (Liquibase 011-overlay-seed-data.xml
#      en 013-posities-hoofddoel.xml) can reference them. Keep these in sync.
#
# Instrument.product URNs reference Open Product producttypen BY CODE
# (urn:pdca:openproduct:producttype:<code>), so the two registers need no
# knowledge of each other's generated uuids.
from datetime import datetime, timezone
from uuid import UUID

from django.contrib.auth import get_user_model
from rest_framework.authtoken.models import Token

from openplan.plannen.models.contactmoment import Contactmoment
from openplan.plannen.models.doel import Doel
from openplan.plannen.models.doelcategorie import DoelCategorie
from openplan.plannen.models.doeltype import DoelType
from openplan.plannen.models.instrument import Instrument
from openplan.plannen.models.instrumenttype import InstrumentType
from openplan.plannen.models.overkoepelendplan import OverkoepelendPlan
from openplan.plannen.models.persoon import Persoon
from openplan.plannen.models.plan import Plan
from openplan.plannen.models.plantype import PlanType
from openplan.plannen.models.relatietype import RelatieType

TOKEN_KEY = "pdca-openplan-dev-token-0123456789abcdef"

user = get_user_model().objects.get(username="admin")
# Token.user is a OneToOneField: drop any token with a different key first.
Token.objects.filter(user=user).exclude(key=TOKEN_KEY).delete()
_, created = Token.objects.get_or_create(key=TOKEN_KEY, defaults={"user": user})
print(f"[openplan-init] API token {'created' if created else 'present'} for user 'admin'")


def dt(y, m, d, h=0):
    return datetime(y, m, d, h, tzinfo=timezone.utc)


# ------------------------------------------------------------ reference data

for t in ("werk", "pip", "inkomen"):
    PlanType.objects.get_or_create(type=t)

DOELCATEGORIEEN = [
    "Inventarisatie", "Ontwikkeling", "Praktisch", "Verkenning", "Plaatsing",
    "Borging", "Analyse", "Herstel", "Controle", "Algemeen", "Hoofddoel",
]
categorie_per_naam = {}
for naam in DOELCATEGORIEEN:
    categorie_per_naam[naam], _ = DoelCategorie.objects.get_or_create(naam=naam)

# Vast gedefinieerde hoofd- en subdoelen (beslissingen 17-08-26): doelen worden
# gekozen uit dit register, geen vrije-tekstdoelen. DoelType.doel_type draagt de
# naam van het doel; de categorie "Hoofddoel" markeert hoofddoel-typen (een plan
# heeft er precies één; de PDCA overlay verwijst ernaar via hoofddoel_type_uuid,
# zie Liquibase 013 — vandaar de FIXED uuids). Overige categorieën groeperen de
# subdoelen in de PDCA-weergave (fasering).
DOELTYPEN = [
    # hoofddoelen (inwonerdomein)
    ("88888888-8888-8888-8888-888888888801", "Duurzaam aan het werk", "Hoofddoel"),
    ("88888888-8888-8888-8888-888888888802", "Financieel zelfredzaam", "Hoofddoel"),
    ("88888888-8888-8888-8888-888888888803", "Zelfstandig meedoen in de samenleving", "Hoofddoel"),
    # hoofddoelen (objectdomein)
    ("88888888-8888-8888-8888-888888888811", "Object structureel veilig in gebruik", "Hoofddoel"),
    # subdoelen (inwonerdomein)
    ("88888888-8888-8888-8888-888888889901", "Financiele situatie in kaart brengen", "Inventarisatie"),
    ("88888888-8888-8888-8888-888888889902", "Competenties en werkervaring beoordelen", "Inventarisatie"),
    ("88888888-8888-8888-8888-888888889903", "Werkfit vaardigheden ontwikkelen", "Ontwikkeling"),
    ("88888888-8888-8888-8888-888888889904", "Kinderopvang regelen", "Praktisch"),
    ("88888888-8888-8888-8888-888888889905", "Orientatie op passende functies", "Verkenning"),
    ("88888888-8888-8888-8888-888888889906", "Duurzame plaatsing realiseren", "Plaatsing"),
    ("88888888-8888-8888-8888-888888889907", "Nazorg en borging", "Borging"),
    ("88888888-8888-8888-8888-888888889908", "Taalniveau verbeteren", "Ontwikkeling"),
    ("88888888-8888-8888-8888-888888889909", "Schulden stabiliseren", "Praktisch"),
    # subdoelen (objectdomein)
    ("88888888-8888-8888-8888-888888889911", "Brandveiligheidsrisico's inventariseren", "Analyse"),
    ("88888888-8888-8888-8888-888888889912", "Geconstateerde gebreken verhelpen", "Herstel"),
    ("88888888-8888-8888-8888-888888889913", "Herinspectie uitvoeren", "Controle"),
    ("88888888-8888-8888-8888-888888889914", "Borging in beheerorganisatie", "Borging"),
]
doeltype_per_naam = {}
for uid, naam, categorie_naam in DOELTYPEN:
    doeltype, dt_created = DoelType.objects.get_or_create(uuid=UUID(uid), defaults={"doel_type": naam})
    if not dt_created and doeltype.doel_type != naam:
        doeltype.doel_type = naam
        doeltype.save()
    if not doeltype.categorieen.filter(naam=categorie_naam).exists():
        doeltype.categorieen.add(categorie_per_naam[categorie_naam])
    doeltype_per_naam[naam] = doeltype

instrumenttypen = {}
for t in ("training", "coaching", "financiele_ondersteuning"):
    instrumenttypen[t], _ = InstrumentType.objects.get_or_create(instrument_type=t)

for naam in (
    "Procesbegeleider", "Regiebehandelaar", "Arbeidscoach", "Schuldhulpverlener",
    "Inwoner / Eigenaar", "Projectleider", "Brandveiligheidsadviseur",
    "Gebouwbeheerder", "Inspecteur", "Aanbieder", "Coach",
):
    RelatieType.objects.get_or_create(naam=naam)

overkoepelend, _ = OverkoepelendPlan.objects.get_or_create(
    uuid=UUID("10000000-0000-0000-0000-000000000001"),
    defaults={"titel": "PDCA Prototype", "status": "actief"},
)
plantype_werk = PlanType.objects.filter(type="werk").first()
print(f"[openplan-init] Reference data present ({DoelType.objects.count()} doeltypen, "
      f"{RelatieType.objects.count()} relatietypen)")


# ----------------------------------------------------------------- personen
# bsn's are 11-proef-valid test numbers; klant/persoonsprofiel URNs would
# point at Open Klant / a profielregister in a full deployment.

def persoon(uuid, bsn):
    obj, _ = Persoon.objects.get_or_create(
        uuid=UUID(uuid),
        defaults={
            "bsn": bsn,
            "klant": f"urn:pdca:openklant:klant:{bsn}",
            "persoonsprofiel": f"urn:pdca:profielen:persoonsprofiel:{bsn}",
        },
    )
    return obj


erika = persoon("99999999-0000-0000-0000-000000000001", "111222333")
jan = persoon("99999999-0000-0000-0000-000000000002", "123456782")
fatima = persoon("99999999-0000-0000-0000-000000000003", "999990019")
beheer_contact = persoon("99999999-0000-0000-0000-000000000004", "234567892")


# ------------------------------------------------------------- demo plannen

def plan(uuid, **defaults):
    obj, created_ = Plan.objects.get_or_create(uuid=UUID(uuid), defaults=defaults)
    return obj, created_


def doel(uuid, plan_obj, persoon_obj, doeltype_naam, **defaults):
    doeltype = doeltype_per_naam[doeltype_naam]
    defaults = {"doeltype": doeltype, "persoon": persoon_obj, **defaults}
    obj, created_ = Doel.objects.get_or_create(uuid=UUID(uuid), defaults=defaults)
    if created_:
        obj.plannen.add(plan_obj)
    elif obj.doeltype_id != doeltype.pk:
        # Herseed op een bestaande omgeving: verwijs naar het benoemde
        # registerdoel i.p.v. het oude anonieme "subdoel"-type.
        obj.doeltype = doeltype
        obj.save(update_fields=["doeltype"])
    return obj


def instrument(uuid, doel_obj, product_code, **defaults):
    defaults = {
        "instrumenttype": instrumenttypen["training"],
        "product": f"urn:pdca:openproduct:producttype:{product_code}",
        **defaults,
    }
    obj, created_ = Instrument.objects.get_or_create(uuid=UUID(uuid), defaults=defaults)
    if created_:
        obj.doelen.add(doel_obj)
    return obj


def contactmoment(uuid, plan_obj, **defaults):
    obj, _ = Contactmoment.objects.get_or_create(uuid=UUID(uuid), defaults={"plan": plan_obj, **defaults})
    return obj


# --- Plan 1: Erika de Goede (inwonerplan, fases Analyse/Uitvoering/Plaatsing) ---
plan_erika, is_new = plan(
    "11111111-1111-1111-1111-111111111111",
    titel="Ontwikkelplan Werk - Erika de Goede",
    notitie="Ik wil duurzaam aan het werk zodat ik financieel onafhankelijk ben",
    status="actief",
    startdatum=dt(2026, 1, 15),
    plantype=plantype_werk,
    overkoepelend_plan=overkoepelend,
    # Bewust zonder zaak: het demoplan wordt "los" geseed en vanuit de GZAC-taak
    # "Plan aanmaken" aan een nieuw dossier gekoppeld (plan = dossier 1:1).
    domeinregister="urn:pdca:brp:persoon:111222333",
    medewerker="urn:pdca:medewerkers:medewerker:s.jansen",
)

doel("22222222-2222-2222-2222-222222222201", plan_erika, erika, "Financiele situatie in kaart brengen",
     titel="Financiele situatie in kaart brengen",
     beschrijving="Volledig overzicht van inkomsten, schulden, toeslagen en lopende financiele verplichtingen.",
     status="afgerond", resultaat="behaald",
     toelichting_resultaat="Schuldsanering niet nodig, aanvraag bijzondere bijstand ingediend.",
     startdatum=dt(2026, 1, 15), einddatum=dt(2026, 2, 12))
doel("22222222-2222-2222-2222-222222222202", plan_erika, erika, "Competenties en werkervaring beoordelen",
     titel="Competenties en werkervaring beoordelen",
     beschrijving="Beroepsprofielanalyse, overdraagbare vaardigheden en scholingsbehoefte vaststellen.",
     status="afgerond", resultaat="behaald",
     startdatum=dt(2026, 1, 20), einddatum=dt(2026, 2, 28))
doel("22222222-2222-2222-2222-222222222203", plan_erika, erika, "Werkfit vaardigheden ontwikkelen",
     titel="Werkfit vaardigheden ontwikkelen",
     beschrijving="Sollicitatievaardigheden, presentatie en werkritme opbouwen via het Werkfit-traject.",
     status="actief", startdatum=dt(2026, 3, 1))
doel("22222222-2222-2222-2222-222222222204", plan_erika, erika, "Kinderopvang regelen",
     titel="Kinderopvang regelen",
     beschrijving="Structurele kinderopvang voor Daan en Lisa zodat werken mogelijk wordt.",
     status="afgerond", resultaat="behaald",
     startdatum=dt(2026, 2, 1), einddatum=dt(2026, 3, 15))
doel("22222222-2222-2222-2222-222222222205", plan_erika, erika, "Orientatie op passende functies",
     titel="Orientatie op passende functies",
     beschrijving="Administratieve functies verkennen die aansluiten op MBO-4 en zes jaar ervaring.",
     status="actief", startdatum=dt(2026, 4, 1))
doel("22222222-2222-2222-2222-222222222206", plan_erika, erika, "Duurzame plaatsing realiseren",
     titel="Duurzame plaatsing realiseren",
     beschrijving="Plaatsing op een passende werkplek met jobcoaching.",
     status="actief", startdatum=dt(2026, 6, 1))
doel("22222222-2222-2222-2222-222222222207", plan_erika, erika, "Nazorg en borging",
     titel="Nazorg en borging",
     beschrijving="Monitoring na plaatsing; terugval voorkomen.",
     status="actief", startdatum=dt(2026, 9, 1))

instrument("44444444-4444-4444-4444-444444444401",
           Doel.objects.get(uuid="22222222-2222-2222-2222-222222222202"), "LEERBAARHEIDSTOETS",
           titel="Leerbaarheidstoets", status="afgerond", resultaat="behaald",
           startdatum=dt(2026, 2, 1), einddatum=dt(2026, 2, 14))
instrument("44444444-4444-4444-4444-444444444402",
           Doel.objects.get(uuid="22222222-2222-2222-2222-222222222203"), "WERKFIT-TRAJECT",
           titel="Werkfit Traject WIO", status="actief", startdatum=dt(2026, 3, 1))
instrument("44444444-4444-4444-4444-444444444403",
           Doel.objects.get(uuid="22222222-2222-2222-2222-222222222206"), "JOBCOACHING",
           titel="Jobcoaching", status="actief", startdatum=dt(2026, 6, 1))

contactmoment("55555555-5555-5555-5555-555555555501", plan_erika,
              status="afgerond", datum=dt(2026, 1, 20, 10),
              notitie="Intakegesprek: motivatie hoog, financiele situatie zorgelijk maar beheersbaar.")
contactmoment("55555555-5555-5555-5555-555555555502", plan_erika,
              status="afgerond", datum=dt(2026, 4, 15, 14),
              notitie="Voortgang goed: analysefase afgerond, Werkfit-traject gestart, kinderopvang geregeld.")
contactmoment("55555555-5555-5555-5555-555555555503", plan_erika,
              status="actief", datum=dt(2026, 9, 15, 10),
              notitie="Geplande voortgangsevaluatie na afronding Werkfit-traject.")

# --- Plan 2: Binnenhof (binnenhof-renovatie, fases Analyse/Uitvoering/Check/Act) ---
plan_binnenhof, _ = plan(
    "33333333-3333-3333-3333-333333333333",
    titel="Brandveiligheidsplan Binnenhof-renovatie",
    notitie="Brandveiligheid van het Binnenhof-complex structureel op orde tijdens en na de renovatie",
    status="actief",
    startdatum=dt(2026, 2, 1),
    plantype=plantype_werk,
    overkoepelend_plan=overkoepelend,
    # Los geseed; koppelen gebeurt vanuit de "Plan aanmaken"-taak in GZAC.
    domeinregister="urn:pdca:objecten:object:binnenhof-001",
    medewerker="urn:pdca:medewerkers:medewerker:p.bakker",
)

doel("22222222-2222-2222-2222-222222222211", plan_binnenhof, beheer_contact, "Brandveiligheidsrisico's inventariseren",
     titel="Brandveiligheidsrisico's inventariseren",
     beschrijving="Volledig brandveiligheidsonderzoek van het complex, inclusief monumentale delen.",
     status="afgerond", resultaat="behaald",
     startdatum=dt(2026, 2, 1), einddatum=dt(2026, 3, 15))
doel("22222222-2222-2222-2222-222222222212", plan_binnenhof, beheer_contact, "Geconstateerde gebreken verhelpen",
     titel="Geconstateerde gebreken verhelpen",
     beschrijving="Brandcompartimentering en detectie-installaties op orde brengen.",
     status="actief", startdatum=dt(2026, 4, 1))
doel("22222222-2222-2222-2222-222222222213", plan_binnenhof, beheer_contact, "Herinspectie uitvoeren",
     titel="Herinspectie uitvoeren",
     beschrijving="Onafhankelijke herinspectie van alle herstelde onderdelen.",
     status="actief", startdatum=dt(2026, 10, 1))
doel("22222222-2222-2222-2222-222222222214", plan_binnenhof, beheer_contact, "Borging in beheerorganisatie",
     titel="Borging in beheerorganisatie",
     beschrijving="BHV-organisatie en periodieke controles structureel beleggen.",
     status="actief", startdatum=dt(2026, 11, 1))

instrument("44444444-4444-4444-4444-444444444411",
           Doel.objects.get(uuid="22222222-2222-2222-2222-222222222211"), "BRANDVEILIGHEIDSONDERZOEK",
           titel="Brandveiligheidsonderzoek", status="afgerond", resultaat="behaald",
           startdatum=dt(2026, 2, 10), einddatum=dt(2026, 3, 10))
instrument("44444444-4444-4444-4444-444444444412",
           Doel.objects.get(uuid="22222222-2222-2222-2222-222222222214"), "BHV-TRAINING",
           titel="BHV-training beheerorganisatie", status="actief", startdatum=dt(2026, 11, 1))

contactmoment("55555555-5555-5555-5555-555555555511", plan_binnenhof,
              status="afgerond", datum=dt(2026, 2, 5, 9),
              notitie="Startoverleg: scope en planning brandveiligheidsonderzoek vastgesteld.")
contactmoment("55555555-5555-5555-5555-555555555512", plan_binnenhof,
              status="actief", datum=dt(2026, 10, 1, 9),
              notitie="Geplande inspectie-evaluatie na herstelwerkzaamheden.")

print(f"[openplan-init] Demo data present: {Plan.objects.count()} plannen, "
      f"{Doel.objects.count()} doelen, {Instrument.objects.count()} instrumenten, "
      f"{Contactmoment.objects.count()} contactmomenten")
