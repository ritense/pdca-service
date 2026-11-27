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

package com.ritense.pdca.service

import com.ritense.pdca.domain.IngeplandInstrument
import com.ritense.pdca.registers.openproduct.OpenProductGateway
import com.ritense.pdca.registers.openproduct.ProductType
import com.ritense.pdca.repository.IngeplandInstrumentRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Ingeplande instrumenten (ARCHITECTURE §6.2): a product chosen for a
 * subdoel is requested only once its startdatum has come and the plan is
 * past concept. Until then it is an [IngeplandInstrument] row and nothing
 * exists in the registers or in GZAC; when it is due the verzoek is filed
 * through [InstrumentAanvraagService] and the row is deleted.
 *
 * [vraagAan] decides between the two: a request for today on a plan past
 * concept files the verzoek right away, anything else is ingepland. The
 * intake plans every instrument it set up ([planIn]). [vraagAanWatKlaarStaat]
 * runs every minute and files what is due; [vraagNuAan] files one on the
 * user's request before its date.
 *
 * Filing is safe to repeat and runs once per row at a time: a row is claimed
 * first (a conditional update with a lease, so of two replicas, or the job
 * and a user, only one files it), and its verzoek carries the row id, so a
 * row whose verzoek was filed before the row could be deleted is only
 * deleted on the next attempt. A business refusal of the registers is kept
 * on the row ([IngeplandInstrument.fout]) and the job leaves it alone until
 * the user retries; a temporary failure is retried on the next run.
 */
@Service
class IngeplandInstrumentService(
    private val repository: IngeplandInstrumentRepository,
    private val instrumentAanvraagService: InstrumentAanvraagService,
    private val openProductGateway: OpenProductGateway,
    private val planConcept: PlanConcept
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** How long a claim on a row lasts; a crashed attempt blocks the next one at most this long. */
        val CLAIM_LEASE: Duration = Duration.ofMinutes(5)

        private const val VERLEDEN = "De startdatum van een instrument ligt niet in het verleden"
        private const val WORDT_AANGEVRAAGD = "Dit instrument wordt op dit moment aangevraagd; probeer het zo opnieuw"
    }

    /** An ingepland instrument as the plan page shows it. */
    data class Ingepland(
        val id: UUID,
        val planUuid: UUID,
        val doelUuid: UUID,
        val producttypeUuid: UUID,
        val productUrn: String,
        val startdatum: String,
        val fout: String?
    )

    /** Outcome of a request: the verzoek filed now, or the instrument ingepland. */
    data class Aanvraag(val verzoek: InstrumentAanvraagService.Verzoek? = null, val ingepland: Ingepland? = null)

    /**
     * Requests a product for a doel from [startdatum] (default today, not in
     * the past). Today on a plan past concept: the verzoek is filed now.
     * Otherwise it is ingepland, after the same check a request makes
     * (422), so a product that cannot be requested is refused now and not on
     * its date. Refused (409) for a doel or plan that no longer runs.
     */
    fun vraagAan(doelUuid: UUID, producttypeUuid: UUID, startdatum: LocalDate?): Aanvraag {
        val vandaag = LocalDate.now()
        val datum = startdatum ?: vandaag
        if (datum.isBefore(vandaag)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, VERLEDEN)
        val (_, plan) = instrumentAanvraagService.lopend(doelUuid)
        if (!datum.isAfter(vandaag) && !planConcept.inConcept(plan.uuid)) {
            return Aanvraag(verzoek = instrumentAanvraagService.start(doelUuid, producttypeUuid, datum))
        }
        val producttype = openProductGateway.getProducttype(producttypeUuid)
        return Aanvraag(ingepland = plan(plan.uuid, doelUuid, producttypeUuid, producttype, datum).weergave())
    }

    /**
     * Plans the product with code [code] under a doel the intake just
     * created; 422 when Open Product does not know it or it cannot be
     * requested, 409 when it is ingepland under that doel already.
     */
    fun planIn(planUuid: UUID, doelUuid: UUID, code: String, startdatum: LocalDate): Ingepland {
        val producttype = openProductGateway.findProducttypeByCode(code) ?: throw ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Open Product kent geen producttype met code '$code'"
        )
        return plan(planUuid, doelUuid, producttype.uuid, producttype, startdatum).weergave()
    }

    private fun plan(planUuid: UUID, doelUuid: UUID, producttypeUuid: UUID, producttype: ProductType, startdatum: LocalDate): IngeplandInstrument {
        instrumentAanvraagService.aanvraagbaar(producttypeUuid, producttype)
        val productUrn = instrumentAanvraagService.productUrn(producttypeUuid, producttype)
        val dubbel = ResponseStatusException(
            HttpStatus.CONFLICT, "'${producttype.naam ?: producttype.code}' is voor dit doel al ingepland"
        )
        if (repository.existsByDoelUuidAndProductUrn(doelUuid, productUrn)) throw dubbel
        val rij = try {
            repository.saveAndFlush(
                IngeplandInstrument(
                    planUuid = planUuid,
                    doelUuid = doelUuid,
                    producttypeUuid = producttypeUuid,
                    productUrn = productUrn,
                    startdatum = startdatum
                )
            )
        } catch (e: DataIntegrityViolationException) {
            throw dubbel
        }
        log.info("Product {} ingepland for doel {} of plan {} from {}", productUrn, doelUuid, planUuid, startdatum)
        return rij
    }

    /** Whether a product is ingepland under a doel. */
    fun isIngepland(doelUuid: UUID, productUrn: String): Boolean = repository.existsByDoelUuidAndProductUrn(doelUuid, productUrn)

    /** The plan's ingeplande instrumenten, earliest startdatum first. */
    fun vanPlan(planUuid: UUID): List<Ingepland> =
        repository.findByPlanUuidOrderByStartdatumAscAangemaaktOpAsc(planUuid).map { it.weergave() }

    /** The ingeplande instrumenten of a doel. */
    fun vanDoel(doelUuid: UUID): List<Ingepland> = repository.findByDoelUuid(doelUuid).map { it.weergave() }

    /**
     * Moves an ingepland instrument to another startdatum (not in the past);
     * also clears an earlier refusal, so the job tries it again.
     */
    fun wijzigStartdatum(id: UUID, startdatum: LocalDate): Ingepland {
        if (startdatum.isBefore(LocalDate.now())) throw ResponseStatusException(HttpStatus.BAD_REQUEST, VERLEDEN)
        val rij = vind(id)
        if (isGeclaimd(rij)) throw ResponseStatusException(HttpStatus.CONFLICT, WORDT_AANGEVRAAGD)
        rij.startdatum = startdatum
        rij.fout = null
        return repository.save(rij).weergave()
    }

    /**
     * Files the verzoek of an ingepland instrument now, before its date, and
     * deletes the row. Refused (409) while the plan is in concept or another
     * attempt is filing it; the registers' refusal is the answer as is.
     */
    fun vraagNuAan(id: UUID): InstrumentAanvraagService.Verzoek {
        val rij = vind(id)
        if (planConcept.inConcept(rij.planUuid)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Het plan is nog een concept; een instrument wordt pas aangevraagd als het plan is vastgesteld"
            )
        }
        if (!claim(rij)) throw ResponseStatusException(HttpStatus.CONFLICT, WORDT_AANGEVRAAGD)
        try {
            return dienIn(rij, LocalDate.now())
                ?: InstrumentAanvraagService.Verzoek(null, openProductGateway.getProducttype(rij.producttypeUuid).naam ?: rij.productUrn)
        } catch (e: Exception) {
            repository.release(id)
            throw e
        }
    }

    /** Removes an ingepland instrument; refused (409) while it is being filed. */
    fun verwijder(id: UUID) {
        val rij = repository.findById(id).orElse(null) ?: return
        if (isGeclaimd(rij)) throw ResponseStatusException(HttpStatus.CONFLICT, WORDT_AANGEVRAAGD)
        repository.verwijder(id)
    }

    /** Drops the ingeplande instrumenten of a doel that is removed or aborted. */
    fun verwijderVanDoel(doelUuid: UUID): Int = repository.verwijderVanDoel(doelUuid)

    /** Drops the ingeplande instrumenten of a plan that is closed. */
    fun verwijderVanPlan(planUuid: UUID): Int = repository.verwijderVanPlan(planUuid)

    // ------------------------------------------------------------ the job

    /**
     * Files the verzoek of every ingepland instrument whose startdatum has
     * come, for the plans past concept. A row the registers refuse keeps the
     * reason; a temporary failure is tried again on the next run.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun vraagAanWatKlaarStaat() {
        val rijen = repository.findAanTeVragen(LocalDate.now(), LocalDateTime.now())
        if (rijen.isEmpty()) return
        rijen.groupBy { it.planUuid }
            .filterKeys { !planConcept.inConcept(it) }
            .values.flatten()
            .forEach { rij -> if (claim(rij)) verwerk(rij) }
    }

    private fun verwerk(rij: IngeplandInstrument) {
        try {
            val verzoek = dienIn(rij)
            log.info(
                "Ingepland instrument {} ({} under doel {}) requested: {}",
                rij.id, rij.productUrn, rij.doelUuid, verzoek?.objectUrl ?: "filed earlier"
            )
        } catch (e: ResponseStatusException) {
            if (e.statusCode.is4xxClientError) {
                val reden = (e.reason ?: e.statusCode.toString()).take(1000)
                repository.markeerFout(rij.id, reden)
                log.warn("Ingepland instrument {} ({} under doel {}) not requested: {}", rij.id, rij.productUrn, rij.doelUuid, reden)
            } else {
                repository.release(rij.id)
                log.warn("Ingepland instrument {} could not be requested now, retried on the next run: {}", rij.id, e.reason)
            }
        } catch (e: Exception) {
            repository.release(rij.id)
            log.warn("Ingepland instrument {} could not be requested now, retried on the next run", rij.id, e)
        }
    }

    /**
     * Files the verzoek of a claimed row and deletes the row; null when an
     * earlier attempt filed it already (the row is only deleted then).
     */
    private fun dienIn(rij: IngeplandInstrument, startdatum: LocalDate = rij.startdatum): InstrumentAanvraagService.Verzoek? {
        if (instrumentAanvraagService.isIngediend(rij.id)) {
            repository.verwijder(rij.id)
            return null
        }
        val verzoek = instrumentAanvraagService.start(rij.doelUuid, rij.producttypeUuid, startdatum, rij.id)
        repository.verwijder(rij.id)
        return verzoek
    }

    private fun claim(rij: IngeplandInstrument): Boolean {
        val nu = LocalDateTime.now()
        return repository.claim(rij.id, nu, nu.plus(CLAIM_LEASE)) == 1
    }

    private fun isGeclaimd(rij: IngeplandInstrument) = rij.geclaimdTot?.isAfter(LocalDateTime.now()) == true

    private fun vind(id: UUID): IngeplandInstrument = repository.findById(id).orElse(null)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dit ingeplande instrument bestaat niet (meer)")

    private fun IngeplandInstrument.weergave() = Ingepland(
        id = id,
        planUuid = planUuid,
        doelUuid = doelUuid,
        producttypeUuid = producttypeUuid,
        productUrn = productUrn,
        startdatum = startdatum.toString(),
        fout = fout
    )
}
