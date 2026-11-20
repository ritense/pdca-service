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

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/** The secret GZAC signs its requests with in the integration tests. */
const val HOST_SECRET = "test-host-secret-0123456789"

/**
 * Base of the integration tests: the whole application in a running servlet
 * container against a real PostgreSQL, with Liquibase applying every
 * changelog and Hibernate validating the entities against the result.
 *
 * All subclasses share one PostgreSQL container (started once, on first use,
 * and removed by Testcontainers when the JVM ends) and, because they declare
 * no configuration of their own, one cached Spring context. Without Docker
 * the tests are skipped; `-PskipIntegrationTests` (tag `integration`) leaves
 * them out of the build entirely.
 *
 * The database is shared, so tests use fresh ids and never assume an empty
 * table.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "pdca.host.secret=$HOST_SECRET",
        "spring.main.banner-mode=off"
    ]
)
abstract class PostgresIntegrationTest {

    companion object {
        /** The major version the app's database runs on (docker-compose.yml, pdca-database). */
        private const val POSTGRES_IMAGE = "postgres:17.4-alpine"

        @JvmField
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>(DockerImageName.parse(POSTGRES_IMAGE)).apply { start() }
    }
}
