package com.ritense.pdca.devsetup

/**
 * Console output of the dev setup. Every line carries the same prefix, so it
 * stays recognisable when it runs in the background next to the app's log.
 */
object Console {
    private const val PREFIX = "[gzac-dev-setup]"

    fun step(title: String) = println("$PREFIX $title")
    fun info(message: String) = println("$PREFIX   $message")
    fun warn(message: String) = println("$PREFIX   WARNING: $message")
    fun fail(message: String) = println("$PREFIX   FAILED: $message")
    fun line(message: String) = println("$PREFIX $message")
}

/** A step that cannot complete; its message says what to do about it. */
class SetupException(message: String) : RuntimeException(message)

/**
 * Polls a condition every [intervalMillis] for a bounded time, saying once
 * what it waits for and then, every [reportEveryMillis], that it still does.
 */
class Waiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val intervalMillis: Long = 5_000,
    private val reportEveryMillis: Long = 120_000,
) {
    /** Whether [condition] holds now or comes true within [seconds]; [hint] says how to get there. */
    fun await(what: String, hint: String, seconds: Long, condition: () -> Boolean): Boolean {
        if (condition()) return true
        if (seconds <= 0) return false
        val start = clock()
        Console.line("Waiting for $what (up to ${seconds / 60} min; $hint)")
        var nextReport = start + reportEveryMillis
        while (clock() - start < seconds * 1000) {
            sleep(intervalMillis)
            if (condition()) {
                Console.line("$what answers")
                return true
            }
            if (clock() >= nextReport) {
                Console.line("Still waiting for $what (${(clock() - start) / 60_000} min)")
                nextReport += reportEveryMillis
            }
        }
        return false
    }
}
