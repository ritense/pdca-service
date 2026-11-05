/*
 * Copyright 2015-2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.pdca.security

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** One API call the browser makes through `/data`. */
data class DataCall(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val body: JsonNode?,
    val context: Map<String, Any?>
)

/** A call the policy allowed: the route it matched and what the user must be allowed. */
data class AllowedCall(val route: DataRoute, val requirement: Requirement)

/**
 * One API operation the bundles may reach through `/data`: method, path
 * (`{}` is one path segment), the query parameters it accepts and how it
 * maps to a [Requirement]. [register] marks the register proxy routes.
 */
class DataRoute(
    val method: String,
    val pattern: String,
    val queryKeys: Set<String>,
    val register: Boolean,
    val requirement: (DataCall, List<String>) -> Requirement
) {
    private val regex = Regex(
        "^" + pattern.split("{}").joinToString("([A-Za-z0-9._~-]+)") { Regex.escape(it) } + "$"
    )

    fun match(call: DataCall): List<String>? =
        if (call.method != method) null else regex.matchEntire(call.path)?.groupValues?.drop(1)

    override fun toString() = "$method ${regex.pattern}"
}

/**
 * The allowlist of everything the browser can reach, and who may: every
 * call through `/data` must match one route here, with only that route's
 * query parameters, or it is refused (403) before anything runs. The
 * register proxy routes are exactly the reads and the few single-object
 * writes the bundles make; every write with a business rule goes through a
 * backend use case and has no proxy route.
 *
 * - Reference data (types, producttypen, a case type's configuration) and
 *   the user's own evaluation session: any verified user.
 * - Plan data: the user must be able to read the plan's dossier
 *   ([PlanScope]); the intake prefill and plan creation check the intake
 *   dossier, an evaluation start the dossier of its tab.
 * - PDCA Beheer (case configuration writes, the case-type lists): GZAC
 *   administrators.
 */
@Component
class DataAccessPolicy(private val scope: PlanScope) {

    /** Every route the bundles can reach, in matching order. */
    val routes: List<DataRoute> = buildList {
        val pdca = "/api/v1/pdca"
        val openPlan = "/openplan/plannen/api/v0"
        val openProduct = "/openproduct/producttypen/api/v1"
        val openKlant = "/openklant/klantinteracties/api/v1"
        val page = arrayOf("page", "pageSize")

        fun api(method: String, pattern: String, vararg query: String, requirement: (DataCall, List<String>) -> Requirement) =
            add(DataRoute(method, pattern, query.toSet(), register = false, requirement = requirement))

        fun proxy(method: String, pattern: String, vararg query: String, requirement: (DataCall, List<String>) -> Requirement) =
            add(DataRoute(method, pattern, query.toSet(), register = true, requirement = requirement))

        val anyUser: (DataCall, List<String>) -> Requirement = { _, _ -> Requirement.AnyUser }
        val admin: (DataCall, List<String>) -> Requirement = { _, _ -> Requirement.Admin }
        val planInPath: (DataCall, List<String>) -> Requirement = { _, v -> scope.plans(listOf(uuid(v[0]))) }
        val planInQuery: (DataCall, List<String>) -> Requirement = { c, _ -> scope.plans(listOf(uuid(c.query["planUuid"]))) }
        val planInBody: (DataCall, List<String>) -> Requirement = { c, _ -> scope.plans(listOf(uuid(c.body.text("planUuid")))) }
        val doelInPath: (DataCall, List<String>) -> Requirement = { _, v -> scope.plans(scope.plansOfDoelen(listOf(uuid(v[0])))) }
        val instrumentInPath: (DataCall, List<String>) -> Requirement = { _, v -> scope.plans(scope.plansOfInstrument(uuid(v[0]))) }

        // ---- register proxy: reference data
        proxy("GET", "$openPlan/doeltype", *page, requirement = anyUser)
        proxy("GET", "$openPlan/relatietype", *page, requirement = anyUser)
        proxy("GET", "$openPlan/plantype", *page, requirement = anyUser)
        proxy("GET", "$openProduct/producttypen", *page, requirement = anyUser)

        // ---- register proxy: plan data
        proxy("GET", "$openPlan/plan/{}", requirement = planInPath)
        proxy("PATCH", "$openPlan/plan/{}") { c, v ->
            onlyFields(c.body, PLAN_FIELDS)
            scope.plans(listOf(uuid(v[0])))
        }
        proxy("GET", "$openPlan/doel", "plannen__uuid", *page) { c, _ -> scope.plans(listOf(uuid(c.query["plannen__uuid"]))) }
        proxy("POST", "$openPlan/doel") { c, _ ->
            // A new doel of exactly one plan, for that plan's persoon and
            // (when it is a subdoel) under a doel of the same plan.
            onlyFields(c.body, NEW_DOEL_FIELDS)
            val plannen = c.body?.get("plannenUuids")?.takeIf { it.isArray && it.size() == 1 } ?: throw PlanScope.denied()
            val planUuid = uuid(plannen[0].asText())
            scope.requireNewDoelFits(planUuid, uuid(c.body.text("persoonUuid")), c.body.optionalText("hoofdDoel")?.let { uuid(it) })
            scope.plans(listOf(planUuid))
        }
        proxy("PATCH", "$openPlan/doel/{}") { c, v ->
            onlyFields(c.body, DOEL_FIELDS)
            c.body.optionalText("status", nullAllowed = false)?.let { if (it !in DOEL_STATUSES) throw PlanScope.denied() }
            scope.plans(scope.plansOfDoelen(listOf(uuid(v[0]))))
        }
        proxy("GET", "$openPlan/instrument", "doelen__uuid__in", *page) { c, _ ->
            val doelen = c.query["doelen__uuid__in"]?.split(',')?.filter { it.isNotBlank() }?.map { uuid(it) } ?: emptyList()
            scope.plans(scope.plansOfDoelen(doelen))
        }
        proxy(
            "GET", "$openKlant/klantcontacten",
            "onderwerpobject__onderwerpobjectidentificatorObjectId",
            "onderwerpobject__onderwerpobjectidentificatorCodeObjecttype",
            "onderwerpobject__onderwerpobjectidentificatorCodeRegister",
            *page
        ) { c, _ ->
            if (c.query["onderwerpobject__onderwerpobjectidentificatorCodeObjecttype"] != "plan" ||
                c.query["onderwerpobject__onderwerpobjectidentificatorCodeRegister"] != "open-plan"
            ) {
                throw PlanScope.denied()
            }
            scope.plans(listOf(uuid(c.query["onderwerpobject__onderwerpobjectidentificatorObjectId"])))
        }

        // ---- PDCA overlay and use cases
        api("PUT", "$pdca/plandetails/{}", requirement = planInPath)
        api("GET", "$pdca/voortgangstatussen", "planUuid") { c, _ ->
            if (c.query["planUuid"] == null) Requirement.AnyUser else scope.plans(listOf(uuid(c.query["planUuid"])))
        }
        api("GET", "$pdca/doeldetails", "planUuid", requirement = planInQuery)
        api("PUT", "$pdca/doeldetails/{}") { c, v ->
            scope.plans(scope.plansOfDoelen(listOf(uuid(v[0]))) + optionalUuids(c.body.optionalText("planUuid")))
        }
        api("GET", "$pdca/instrumentdetails", "planUuid", requirement = planInQuery)
        api("PUT", "$pdca/instrumentdetails/{}") { c, v ->
            scope.plans(scope.plansOfInstrument(uuid(v[0])) + optionalUuids(c.body.optionalText("planUuid")))
        }
        api("GET", "$pdca/contactmomentdetails", "planUuid", requirement = planInQuery)
        api("POST", "$pdca/contactmomenten", requirement = planInBody)
        api("PATCH", "$pdca/contactmomenten/{}") { _, v -> scope.plans(listOfNotNull(scope.planOfContactmoment(uuid(v[0])))) }
        api("GET", "$pdca/evaluaties", "planUuid", requirement = planInQuery)

        api("GET", "$pdca/acties", "planUuid", requirement = planInQuery)
        api("POST", "$pdca/acties") { c, _ -> scope.plans(scope.plansOfDoelen(listOf(uuid(c.body.text("doelUuid"))))) }
        val actieInPath: (DataCall, List<String>) -> Requirement = { _, v -> scope.plans(scope.plansOfActie(uuid(v[0]))) }
        api("PATCH", "$pdca/acties/{}", requirement = actieInPath)
        api("POST", "$pdca/acties/{}/approve", requirement = actieInPath)
        api("POST", "$pdca/acties/{}/reject", requirement = actieInPath)

        api("GET", "$pdca/betrokkenen", "planUuid", requirement = planInQuery)
        api("POST", "$pdca/betrokkenen", requirement = planInBody)
        val betrokkeneInPath: (DataCall, List<String>) -> Requirement = { _, v -> scope.plans(listOf(scope.planOfBetrokkene(uuid(v[0])))) }
        api("PUT", "$pdca/betrokkenen/{}/procesbegeleider", requirement = betrokkeneInPath)
        api("DELETE", "$pdca/betrokkenen/{}", requirement = betrokkeneInPath)

        api("GET", "$pdca/dossiers/{}/plan") { _, v -> scope.dossier(v[0]) }
        api("GET", "$pdca/dossiers/{}/plan-prefill") { _, v -> scope.dossier(v[0]) }
        api("POST", "$pdca/plan-intake") { c, _ -> scope.dossier(c.body.text("documentId")) }

        api("POST", "$pdca/instrumenten/start") { c, _ -> scope.plans(scope.plansOfDoelen(listOf(uuid(c.body.text("doelUuid"))))) }
        api("GET", "$pdca/instrumenten/verzoeken", "planUuid", requirement = planInQuery)
        api("POST", "$pdca/instrumenten/{}/starten", requirement = instrumentInPath)
        api("POST", "$pdca/instrumenten/{}/afronden", requirement = instrumentInPath)
        api("POST", "$pdca/instrumenten/{}/afbreken", requirement = instrumentInPath)
        api("DELETE", "$pdca/doelen/{}", requirement = doelInPath)
        api("POST", "$pdca/doelen/{}/afbreken", requirement = doelInPath)
        api("PUT", "$pdca/plannen/{}/hoofddoel", requirement = planInPath)
        api("GET", "$pdca/plannen/{}/afsluitcheck", requirement = planInPath)
        api("POST", "$pdca/plannen/{}/afronden", requirement = planInPath)
        api("POST", "$pdca/plannen/{}/afbreken", requirement = planInPath)
        api("POST", "$pdca/plannen/{}/heractiveren", requirement = planInPath)

        // ---- the user's own evaluation session; a start checks the dossier of the tab
        api("POST", "$pdca/evaluation-sessions") { c, _ -> scope.dossier(c.context["documentId"] as? String) }
        api("GET", "$pdca/evaluation-sessions/current", requirement = anyUser)
        api("POST", "$pdca/evaluation-sessions/current/draft", requirement = anyUser)
        api("GET", "$pdca/evaluation-sessions/current/changes", requirement = anyUser)
        api("POST", "$pdca/evaluation-sessions/current/changes", requirement = anyUser)
        api("POST", "$pdca/evaluation-sessions/current/change-toelichting", requirement = anyUser)
        api("POST", "$pdca/evaluation-sessions/current/complete", requirement = anyUser)
        api("POST", "$pdca/evaluation-sessions/current/cancel", requirement = anyUser)

        // ---- case configuration: reads for the tabs, the rest PDCA Beheer
        api("GET", "/api/v1/admin/phase-configs/{}", requirement = anyUser)
        api("GET", "/api/v1/admin/phase-configs", requirement = admin)
        api("POST", "/api/v1/admin/phase-configs", requirement = admin)
        api("PUT", "/api/v1/admin/phase-configs/{}", requirement = admin)
        api("DELETE", "/api/v1/admin/phase-configs/{}", requirement = admin)
        api("GET", "$pdca/case-definitions", requirement = admin)
        api("GET", "$pdca/case-definitions/{}/document-paths", requirement = admin)

        // ---- display stubs for registers out of scope (no register data behind them)
        api("GET", "/api/v1/registers/personen/{}", requirement = anyUser)
        api("GET", "/api/v1/registers/objecten/{}", requirement = anyUser)
    }

    /**
     * The route [call] matches and what the user must be allowed for it;
     * 403 when the path is not a plain path, no route matches (also for a
     * query parameter the route does not take) or the target does not
     * resolve; 400 for a malformed id.
     */
    fun resolve(call: DataCall): AllowedCall {
        if (!isSafePath(call.path)) throw PlanScope.denied()
        for (route in routes) {
            val vars = route.match(call) ?: continue
            if (!route.queryKeys.containsAll(call.query.keys)) throw PlanScope.denied()
            return AllowedCall(route, route.requirement(call, vars))
        }
        throw PlanScope.denied()
    }

    companion object {
        private val SAFE_PATH = Regex("^(/[A-Za-z0-9._~-]+)+$")

        /** Plain absolute segments only: no empty, `.` or `..` segment, nothing encoded. */
        fun isSafePath(path: String): Boolean =
            SAFE_PATH.matches(path) && path.split('/').drop(1).none { it == "." || it == ".." }

        /** The plan fields the bundles edit on the register directly; status changes are use cases. */
        val PLAN_FIELDS = setOf("notitie")

        /** The fields of a doel the bundles create (a subdoel under the plan's hoofddoel). */
        val NEW_DOEL_FIELDS = setOf("plannenUuids", "persoonUuid", "doeltypeUuid", "titel", "beschrijving", "startdatum", "hoofdDoel")

        /** The doel fields the bundles edit directly: its texts, its doeltype and completing it; never its plan, persoon or hoofddoel. */
        val DOEL_FIELDS = setOf("titel", "beschrijving", "doeltypeUuid", "status", "resultaat", "einddatum", "toelichtingResultaat")
        val DOEL_STATUSES = setOf("afgerond")

        private fun onlyFields(body: JsonNode?, allowed: Set<String>) {
            if (body == null || !body.isObject) throw PlanScope.denied()
            if (body.fieldNames().asSequence().any { it !in allowed }) throw PlanScope.denied()
        }

        /** A required text field: its value, or null when it is absent, null, blank or not text (the caller refuses null). */
        private fun JsonNode?.text(field: String): String? =
            this?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        /**
         * An optional text field the policy checks: null when it is absent
         * (or an explicit null, where [nullAllowed]); its value when it is a
         * non-blank string; refused (403) when it is anything else, so a
         * value the check cannot read never reaches the register or handler
         * unchecked.
         */
        private fun JsonNode?.optionalText(field: String, nullAllowed: Boolean = true): String? {
            val node = this?.get(field) ?: return null
            if (node.isNull && nullAllowed) return null
            if (!node.isTextual || node.asText().isBlank()) throw PlanScope.denied()
            return node.asText()
        }

        private fun uuid(value: String?): UUID = try {
            UUID.fromString(value ?: throw PlanScope.denied())
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ongeldige aanvraag")
        }

        private fun optionalUuids(value: String?): List<UUID> = listOfNotNull(value?.let { uuid(it) })
    }
}
