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

package com.ritense.pdca.it

import com.ritense.pdca.domain.DocumentAanvraag
import com.ritense.pdca.domain.DocumentAanvraagStatus
import com.ritense.pdca.domain.DocumentSoort
import com.ritense.pdca.repository.DocumentAanvraagRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The document requests (`document_aanvraag`) on PostgreSQL: a process
 * without a recorded request only claims a young unclaimed one and skips a
 * row another process holds; recording the process and failing an
 * unclaimed request are conditional; a request open too long is ended; and
 * the database keeps one open request per document and one request per
 * process.
 */
class DocumentAanvraagRepositoryIntegrationTest : PostgresIntegrationTest() {

    @Autowired private lateinit var repository: DocumentAanvraagRepository
    @Autowired private lateinit var transactions: TransactionTemplate

    private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

    private fun aanvraag(
        dossierId: String,
        status: DocumentAanvraagStatus = DocumentAanvraagStatus.AANGEVRAAGD,
        aangevraagdOp: LocalDateTime = now(),
        processInstanceId: String? = null,
        bronUuid: UUID = UUID.randomUUID()
    ) = repository.save(
        DocumentAanvraag(
            planUuid = UUID.randomUUID(), dossierId = dossierId, soort = DocumentSoort.EVALUATIEVERSLAG,
            bronUuid = bronUuid, status = status, processInstanceId = processInstanceId, gegevens = "{}",
            aangevraagdOp = aangevraagdOp, bijgewerktOp = aangevraagdOp
        )
    )

    private fun dossier() = "dossier-${UUID.randomUUID()}"

    @Test
    fun `a process without recorded request claims the oldest young unclaimed one of its dossier`() {
        val dossier = dossier()
        aanvraag(dossier, aangevraagdOp = now().minusMinutes(20))
        aanvraag(dossier, aangevraagdOp = now().minusMinutes(3), processInstanceId = "p-${UUID.randomUUID()}")
        val oudste = aanvraag(dossier, aangevraagdOp = now().minusMinutes(2))
        aanvraag(dossier, aangevraagdOp = now().minusMinutes(1))
        aanvraag(dossier(), aangevraagdOp = now().minusMinutes(5))

        val claimable = transactions.execute { repository.findClaimable(dossier, now().minusMinutes(10)) }

        assertEquals(oudste.id, claimable?.id)
    }

    @Test
    fun `two processes claiming at once each get their own request`() {
        val dossier = dossier()
        aanvraag(dossier, aangevraagdOp = now().minusMinutes(2))
        aanvraag(dossier, aangevraagdOp = now().minusMinutes(1))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<UUID?> {
                transactions.execute {
                    val claimed = repository.findClaimable(dossier, now().minusMinutes(10))
                    held.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    claimed?.id
                }
            }
            held.await(10, TimeUnit.SECONDS)
            val second = transactions.execute { repository.findClaimable(dossier, now().minusMinutes(10))?.id }
            release.countDown()

            assertNotEquals(first.get(10, TimeUnit.SECONDS), second)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a process is recorded once, and not on a request another process has`() {
        val process = "p-${UUID.randomUUID()}"
        val eerste = aanvraag(dossier())
        val tweede = aanvraag(dossier())
        val geclaimd = aanvraag(dossier(), processInstanceId = "p-${UUID.randomUUID()}")

        assertEquals(1, repository.recordProcess(eerste.id, process))
        assertEquals(0, repository.recordProcess(tweede.id, process))
        assertEquals(0, repository.recordProcess(geclaimd.id, "p-${UUID.randomUUID()}"))
        assertEquals(process, repository.findById(eerste.id).orElseThrow().processInstanceId)
        assertNull(repository.findById(tweede.id).orElseThrow().processInstanceId)
        assertThrows<DataIntegrityViolationException> { aanvraag(dossier(), processInstanceId = process) }
    }

    @Test
    fun `only an unclaimed open request fails on a start that did not work`() {
        val unclaimed = aanvraag(dossier())
        val claimed = aanvraag(dossier(), status = DocumentAanvraagStatus.BEZIG, processInstanceId = "p-${UUID.randomUUID()}")

        assertEquals(1, repository.failUnclaimed(unclaimed.id, "niet gestart", now()))
        assertEquals(0, repository.failUnclaimed(claimed.id, "niet gestart", now()))
        val failed = repository.findById(unclaimed.id).orElseThrow()
        assertEquals(DocumentAanvraagStatus.MISLUKT, failed.status)
        assertNull(failed.gegevens)
        assertEquals(DocumentAanvraagStatus.BEZIG, repository.findById(claimed.id).orElseThrow().status)
    }

    @Test
    fun `requests open too long are ended and a document has one open request`() {
        val bron = UUID.randomUUID()
        val stale = aanvraag(dossier(), status = DocumentAanvraagStatus.BEZIG, aangevraagdOp = now().minusMinutes(30), bronUuid = bron)
        assertThrows<DataIntegrityViolationException> { aanvraag(dossier(), bronUuid = bron) }

        repository.expireOpen(now().minusMinutes(10), "niet op tijd", now())

        val ended = repository.findById(stale.id).orElseThrow()
        assertEquals(DocumentAanvraagStatus.MISLUKT, ended.status)
        assertEquals("niet op tijd", ended.foutmelding)
        assertEquals(DocumentAanvraagStatus.AANGEVRAAGD, aanvraag(dossier(), bronUuid = bron).status)
    }
}
