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

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals

/**
 * The application starts against PostgreSQL: Liquibase applies the whole
 * changelog and Hibernate's schema validation (`ddl-auto: validate`) finds
 * every entity matching the resulting tables.
 */
class SchemaIntegrationTest : PostgresIntegrationTest() {

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var environment: Environment

    @Test
    fun `the context starts with the entities validated against the migrated schema`() {
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals(17, jdbcTemplate.queryForObject("SHOW server_version_num", String::class.java)!!.toInt() / 10000)
    }

    @Test
    fun `every changeset of the master changelog is applied, in order`() {
        val expected = changeSets(MASTER)
        val applied = jdbcTemplate.query("SELECT filename, id FROM databasechangelog ORDER BY orderexecuted") { rs, _ ->
            rs.getString("filename").removePrefix("classpath:").removePrefix("/") to rs.getString("id")
        }

        assertEquals(expected, applied)
    }

    /** The (file, changeset id) pairs of [file], following its includes in order. */
    private fun changeSets(file: String): List<Pair<String, String>> {
        val document = ClassPathResource(file).inputStream.use {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it)
        }
        val children = document.documentElement.childNodes
        return (0 until children.length).map { children.item(it) }.filterIsInstance<Element>().flatMap { element ->
            when (element.localName) {
                "include" -> changeSets(element.getAttribute("file"))
                "changeSet" -> listOf(file to element.getAttribute("id"))
                else -> emptyList()
            }
        }
    }

    private companion object {
        const val MASTER = "config/liquibase/changelog-master.xml"
    }
}
