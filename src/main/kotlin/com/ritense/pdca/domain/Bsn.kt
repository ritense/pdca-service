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

package com.ritense.pdca.domain

/** The burgerservicenummer check (elfproef), as the intake form's bsn generator applies it. */
object Bsn {

    /** Nine digits whose weighted sum (9..2 for the first eight, -1 for the last) is a multiple of 11. */
    fun isValid(value: String?): Boolean {
        if (value == null || value.length != 9 || !value.all { it in '0'..'9' } || value.all { it == '0' }) return false
        val digits = value.map { it - '0' }
        val sum = (0 until 8).sumOf { digits[it] * (9 - it) } - digits[8]
        return sum % 11 == 0
    }
}
