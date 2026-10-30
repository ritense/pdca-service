/*
 * Gradle init script that adds the PDCA autodeployment set to the GZAC dev app
 * of the Valtimo monorepo without changing that repository. Run GZAC with it
 * from the monorepo root:
 *
 *   ./gradlew :backend:apps:dev:bootRun -I <pdca-app>/gzac/autodeployment/gzac-dev.init.gradle.kts
 *
 * It puts on the dev app's classpath: gzac/autodeployment/resources (plugin
 * configurations, object management, external plugin descriptor, the dev
 * zaaktype link of the instrument dossier), every case definition under
 * gzac/case-definitions and the built register plugin packages (as
 * plugin-packages/<name>-1.0.0.zip, uploaded to the plugin host by the
 * descriptor). GZAC's autodeployment picks all of it up at boot.
 *
 * Two environment values of the dev app are overridden, because the stack's
 * containers know the host as host.docker.internal and that name must appear
 * in the notification subscription (objecttype filter) and in the callback
 * URL Open Notificaties verifies. The JDK hosts file makes GZAC itself resolve
 * that name on macOS, where the host does not.
 */
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

val initScript = gradle.startParameter.initScripts.first { it.name == "gzac-dev.init.gradle.kts" }
val pdcaRoot = initScript.absoluteFile.parentFile.parentFile.parentFile
val caseKeys = listOf("inwonerplan", "binnenhof-renovatie", "intake-werk-participatie", "renovatie-intake", "instrument-verzoek")
val registerPlugins = listOf("openplan", "openproduct", "openklant", "objecten")
val environmentOverrides = mapOf(
    "VALTIMO_API_URL" to "http://host.docker.internal:8080/api/v1/",
    "VALTIMO_OBJECTTYPEN_API_URL" to "http://host.docker.internal:8011/api/v2/"
)

allprojects {
    if (path != ":backend:apps:dev") return@allprojects
    afterEvaluate {
        val main = extensions.getByType(SourceSetContainer::class.java).getByName("main")
        main.resources.srcDir(pdcaRoot.resolve("gzac/autodeployment/resources"))
        caseKeys.forEach { main.resources.srcDir(pdcaRoot.resolve("gzac/case-definitions/$it")) }

        tasks.named("processResources", ProcessResources::class.java) {
            registerPlugins.forEach { plugin ->
                from(pdcaRoot.resolve("plugins/$plugin/dist")) {
                    include("*.zip")
                    into("plugin-packages")
                }
            }
        }

        tasks.named("bootRun", JavaExec::class.java) {
            // The dev app loads .env.properties in a doFirst action of its own;
            // this action is inserted right after it so the overrides win.
            actions.add(minOf(1, actions.size), Action<Task> { (this as JavaExec).environment(environmentOverrides) })
            // The JDK hosts file replaces the system resolver entirely, so the
            // machine's own name (the process engine asks for it) goes in too.
            val hostsFile = layout.buildDirectory.file("pdca/jdk-hosts").get().asFile
            doFirst {
                hostsFile.parentFile.mkdirs()
                val localName = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("localhost")
                hostsFile.writeText(
                    pdcaRoot.resolve("gzac/autodeployment/dev/jdk-hosts").readText().trimEnd() + "\n127.0.0.1 $localName\n"
                )
            }
            jvmArgs("-Djdk.net.hosts.file=$hostsFile")
        }
    }
}
