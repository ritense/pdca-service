package com.ritense.pdca.devsetup

import java.io.File
import java.net.InetAddress
import kotlin.system.exitProcess

/**
 * Configures a running local GZAC (the Valtimo dev app with its
 * gzac-docker-compose stack) for the PDCA app through GZAC's admin REST API,
 * logged in as the dev administrator. Every step looks up what is there and
 * creates or updates it, so it is safe to run again. See
 * gzac/dev-setup/README.md.
 *
 * Arguments:
 *  - `--repo=<dir>` the pdca-app checkout (default: the working directory);
 *  - `--wait=<seconds>` how long to wait for GZAC and then for the PDCA app
 *    (default 0: stop when GZAC is not up, skip what needs the app when it is
 *    not up);
 *  - `--exit-with-parent` stop when the process that started it ends;
 *  - `--check` change nothing, only report what is missing ([SetupCheck]).
 */
fun main(args: Array<String>) {
    val options = args.associate { it.removePrefix("--").substringBefore("=") to it.substringAfter("=", "true") }
    if (options["exit-with-parent"] == "true") {
        ProcessHandle.current().parent().ifPresent { parent -> parent.onExit().thenRun { Runtime.getRuntime().halt(0) } }
    }
    val repoDir = File(options["repo"] ?: ".").absoluteFile
    val settings = Settings.fromEnvironment()
    if (options["check"] == "true") exitProcess(SetupCheck(repoDir, settings).run())
    exitProcess(DevSetup(repoDir, settings).run(waitSeconds = options["wait"]?.toLong() ?: 0))
}

class DevSetup(private val repoDir: File, private val settings: Settings) {
    private val api = GzacApi(settings)
    private val failedSteps = mutableListOf<String>()

    /** Waits up to [waitSeconds] for GZAC, configures it, then waits as long for the app; returns the exit code. */
    fun run(waitSeconds: Long): Int {
        val gzacUp = Waiter().await("GZAC at ${settings.gzacUrl}", START_GZAC, waitSeconds) { api.ready() }
        if (!gzacUp) {
            Console.line("Not finished: GZAC at ${settings.gzacUrl} does not answer or refuses the dev login ($START_GZAC); then $RETRY")
            return 1
        }
        val values = step("Dev values (${DevValues.PATH})") { DevValues.load(repoDir.resolve(DevValues.PATH), System.getenv()) }
        if (values == null) {
            Console.line("Not finished: the dev values do not load (above); $RETRY")
            return 1
        }
        gzacSteps(values)
        val app = PdcaApp(api, values)
        val appUp = Waiter().await("the PDCA app at ${app.healthUrl}", START_APP, waitSeconds) { api.isUp(app.healthUrl) }
        if (appUp) appSteps(app, values)
        Console.line(
            when {
                failedSteps.isNotEmpty() -> "Not finished: ${failedSteps.joinToString(", ")} failed (above); fix it, then $RETRY"
                !appUp -> "Not finished: the PDCA configuration and the case definitions need the running PDCA app " +
                    "(${app.healthUrl}; $START_APP); then $RETRY"
                else -> "GZAC set up for PDCA: open ${values.app.frontendOrigins.firstOrNull() ?: settings.gzacUrl}"
            }
        )
        return if (failedSteps.isEmpty() && appUp) 0 else 1
    }

    private fun gzacSteps(values: DevValues) {
        step("Name resolution") { checkNameResolution() }
        step("Verzoek objecttype in the stack's Objecttypen and Objecten API") { RegisterScripts(repoDir).run() }
        step("Register plugins on the plugin host") { RegisterPlugins(api, values, repoDir).apply() }
        step("Verzoek route") { VerzoekRoute(api, values).apply() }
        step("PDCA app as external app") { PdcaApp(api, values).registerHost() }
    }

    private fun appSteps(app: PdcaApp, values: DevValues) {
        val configurationId = step("PDCA configuration") {
            app.configure(RegisterPlugins(api, values, repoDir).existingConfigurations())
        } ?: return
        step("Case definitions") { CaseDefinitions(api, values, repoDir).apply(configurationId) }
    }

    /**
     * The stack's containers hand out URLs with host.docker.internal (verzoek
     * objects, objecttypes), which GZAC fetches, so this machine must resolve it.
     */
    private fun checkNameResolution() {
        if (runCatching { InetAddress.getByName("host.docker.internal") }.isSuccess) {
            Console.info("host.docker.internal resolves")
        } else {
            Console.warn(
                "this machine does not resolve host.docker.internal, so GZAC cannot fetch verzoeken; " +
                    "add '127.0.0.1 host.docker.internal' to /etc/hosts"
            )
        }
    }

    private fun <T> step(title: String, action: () -> T): T? {
        Console.step(title)
        return try {
            action()
        } catch (e: SetupException) {
            Console.fail(e.message ?: "")
            failedSteps += title
            null
        } catch (e: Exception) {
            Console.fail("${e::class.simpleName}: ${e.message}")
            failedSteps += title
            null
        }
    }

    private companion object {
        const val START_GZAC = "start it in the Valtimo checkout: ./gradlew :backend:apps:dev:bootRunWithDocker"
        const val START_APP = "./gradlew bootRun starts it"
        const val RETRY = "run ./gradlew gzacDevSetup"
    }
}

/** Runs registers/setup-registers.sh: the verzoek objecttype in the stack's registers, through docker exec. */
class RegisterScripts(private val repoDir: File) {
    fun run() {
        val script = repoDir.resolve("gzac/dev-setup/registers/setup-registers.sh")
        val process = ProcessBuilder("/bin/sh", script.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        val exit = process.waitFor()
        output.filter { it.isNotBlank() }.forEach { Console.info(it.trim()) }
        if (exit != 0) throw SetupException("setup-registers.sh stopped with exit code $exit; product requests will not reach GZAC")
    }
}
