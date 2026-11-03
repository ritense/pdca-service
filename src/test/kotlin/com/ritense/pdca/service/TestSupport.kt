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

import org.mockito.Mockito
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * An in-memory Spring Data repository for service tests: the CRUD methods
 * the services use plus derived `findBy<Prop>[And<Prop>]`, `findBy<Prop>In`,
 * `deleteBy<Prop>[In]` and `existsById` queries, answered from a map keyed
 * by [idProperty]. [custom] answers any other method by name.
 */
class InMemoryRepository<T : Any>(private val type: KClass<T>, private val idProperty: String) {

    val rows = linkedMapOf<Any, T>()

    fun idOf(row: Any): Any = property(row, idProperty)!!

    @Suppress("UNCHECKED_CAST")
    fun <R : Any> proxy(repository: KClass<R>, custom: Map<String, (Array<Any?>) -> Any?> = emptyMap()): R =
        Proxy.newProxyInstance(repository.java.classLoader, arrayOf(repository.java)) { _, method, rawArgs ->
            val args = rawArgs ?: emptyArray()
            custom[method.name]?.let { return@newProxyInstance it(args) }
            when (method.name) {
                "save", "saveAndFlush" -> args[0]!!.also { rows[idOf(it)] = it as T }
                "saveAll" -> (args[0] as Iterable<*>).map { it!!.also { r -> rows[idOf(r)] = r as T } }
                "findById" -> Optional.ofNullable(rows[args[0]])
                "existsById" -> rows.containsKey(args[0])
                "findAll" -> if (args.isEmpty()) rows.values.toList() else rows.values.toList()
                "findAllById" -> (args[0] as Iterable<*>).mapNotNull { rows[it] }
                "deleteById" -> { rows.remove(args[0]); null }
                "delete" -> { rows.remove(idOf(args[0]!!)); null }
                "deleteAll" -> { if (args.isEmpty()) rows.clear() else (args[0] as Iterable<*>).forEach { rows.remove(idOf(it!!)) }; null }
                "count" -> rows.size.toLong()
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "InMemoryRepository<${type.simpleName}>"
                else -> derived(method.name, args, method.returnType)
            }
        } as R

    private fun derived(name: String, args: Array<Any?>, returnType: Class<*>): Any? {
        val (prefix, rest) = when {
            name.startsWith("findBy") -> "find" to name.removePrefix("findBy")
            name.startsWith("deleteBy") -> "delete" to name.removePrefix("deleteBy")
            else -> throw UnsupportedOperationException("InMemoryRepository does not support $name")
        }
        val conditions = rest.substringBefore("OrderBy").split("And").mapIndexed { i, part ->
            val isIn = part.endsWith("In") && type.memberProperties.none { it.name == decap(part) }
            val prop = decap(if (isIn) part.removeSuffix("In") else part)
            val matches: (T) -> Boolean = { row ->
                val value = property(row, prop)
                if (isIn) (args[i] as Collection<*>).contains(value) else value == args[i]
            }
            matches
        }
        val matches = rows.values.filter { row -> conditions.all { it(row) } }
        if (prefix == "delete") {
            matches.forEach { rows.remove(idOf(it)) }
            return null
        }
        return if (Collection::class.java.isAssignableFrom(returnType)) matches else matches.firstOrNull()
    }

    private fun decap(s: String) = s.replaceFirstChar { it.lowercase() }

    private fun property(row: Any, name: String): Any? =
        row::class.memberProperties.first { it.name == name }.getter.call(row)
}

/** A TransactionTemplate whose transactions do nothing: the in-memory repositories need none. */
fun noOpTransactions(): TransactionTemplate = TransactionTemplate(object : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
    override fun doCommit(status: DefaultTransactionStatus) {}
    override fun doRollback(status: DefaultTransactionStatus) {}
})

/** Mockito's any() for a non-null Kotlin parameter of a mocked class. */
@Suppress("UNCHECKED_CAST")
fun <T> anyValue(): T {
    Mockito.any<T>()
    return null as T
}

/** Mockito's eq() for a non-null Kotlin parameter of a mocked class. */
fun <T> eqValue(value: T): T {
    Mockito.eq(value)
    return value
}
