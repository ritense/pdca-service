import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.userinput.UserInputHandler

plugins {
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
    kotlin("plugin.jpa") version "2.1.20"
}

group = "com.ritense"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    // The integration tests (tag "integration") run against PostgreSQL in
    // Testcontainers and skip themselves without Docker; -PskipIntegrationTests
    // leaves them out altogether.
    useJUnitPlatform {
        if (project.hasProperty("skipIntegrationTests")) excludeTags("integration")
    }
    // Some tests read files outside the classpath (GzacConfigurationTest, the
    // register contract footprint): the GZAC configuration and the register
    // plugins' manifests and allowlists. A change there reruns the tests.
    inputs.dir("gzac/case-definitions").withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("gzacCaseDefinitions")
    inputs.file("gzac/dev-setup/dev-values.json").withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("gzacDevValues")
    listOf("openplan", "openproduct", "openklant", "objecten").forEach { plugin ->
        inputs.files("plugins/$plugin/manifest.json", "plugins/$plugin/src")
            .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("registerPlugin-$plugin")
    }
}

tasks.register<Exec>("dockerUp") {
    group = "docker"
    description = "Start the PDCA app's dependencies via docker compose and wait until they are healthy"
    commandLine("/usr/local/bin/docker", "compose", "up", "-d", "--wait")
    workingDir = projectDir
    environment("PATH", "/usr/local/bin:/usr/bin:/bin")
}

// The compose stack logs every visitor of a register admin in as `admin`
// (docker/register-dev/pdca_dev.py), so these pages open logged in.
// Skip with -PopenRegisterAdmins=false.
val openRegisterAdmins = providers.gradleProperty("openRegisterAdmins").orElse("true")

tasks.register("openRegisterAdmins") {
    group = "application"
    description = "Open the admin pages of Open Plan, Open Product and Open Klant in the browser"
    mustRunAfter("dockerUp")
    onlyIf { openRegisterAdmins.get() != "false" }
    doLast {
        val urls = listOf(7501, 7502, 7504).map { "http://localhost:$it/admin/" }
        val os = System.getProperty("os.name").lowercase()
        val commands = when {
            "mac" in os -> listOf(listOf("open") + urls)
            "win" in os -> urls.map { listOf("cmd", "/c", "start", "", it) }
            else -> urls.map { listOf("xdg-open", it) }
        }
        commands.forEach { ProcessBuilder(it).inheritIO().start().waitFor() }
    }
}

// Local development shows the demo data of the display stubs (BRP, objecten).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment("PDCA_DISPLAY_STUBS", System.getenv("PDCA_DISPLAY_STUBS") ?: "true")
}

// With a Valtimo checkout present, bootRunWithDocker first shows the state of
// the local GZAC and offers to set it up for PDCA (see "Local GZAC dev setup"
// below).
tasks.register("bootRunWithDocker") {
    group = "application"
    description = "Start docker compose, open the register admins, run the PDCA app and, if wanted, set up the local GZAC for PDCA"
    dependsOn("dockerUp", "openRegisterAdmins", "buildPlugins", "devSetupClasses")
    finalizedBy("bootRun")
}

// ---------------------------------------------------------------------------
// Container image tasks. The Dockerfile is fully self-contained (builds the
// frontend bundles and the jar inside the image), so none of these need a
// local Node or a prior ./gradlew build.

tasks.register<Exec>("dockerBuild") {
    group = "docker"
    description = "Build the pdca-app container image (tags: $version, latest)"
    commandLine(
        "/usr/local/bin/docker", "build",
        "-t", "pdca-app:$version",
        "-t", "pdca-app:latest",
        ".",
    )
    workingDir = projectDir
    environment("PATH", "/usr/local/bin:/usr/bin:/bin")
}

tasks.register<Exec>("dockerUpAll") {
    group = "docker"
    description = "Run the entire stack (app + dependencies) in Docker"
    commandLine("/usr/local/bin/docker", "compose", "--profile", "app", "up", "-d", "--build")
    workingDir = projectDir
    environment("PATH", "/usr/local/bin:/usr/bin:/bin")
}

tasks.register<Exec>("dockerDownAll") {
    group = "docker"
    description = "Stop and remove the entire Docker stack (app + dependencies)"
    commandLine("/usr/local/bin/docker", "compose", "--profile", "app", "down")
    workingDir = projectDir
    environment("PATH", "/usr/local/bin:/usr/bin:/bin")
}

// Build-and-push for the development cluster, e.g.:
//   ./gradlew dockerBuildPush -PdockerRegistry=registry.example.com/team
//   ./gradlew dockerBuildPush -PdockerRegistry=... -PdockerTag=pr-42 -PdockerPlatforms=linux/amd64,linux/arm64
tasks.register<Exec>("dockerBuildPush") {
    group = "docker"
    description = "Build the image for the dev cluster (buildx) and push it (-PdockerRegistry=..., optional -PdockerTag=..., -PdockerPlatforms=...)"
    val registry = providers.gradleProperty("dockerRegistry").orNull
    val tag = providers.gradleProperty("dockerTag").orNull ?: version.toString()
    val platforms = providers.gradleProperty("dockerPlatforms").orNull ?: "linux/amd64"
    doFirst {
        if (registry == null) {
            throw GradleException("Set the target registry: ./gradlew dockerBuildPush -PdockerRegistry=<registry>/<namespace>")
        }
    }
    commandLine(
        "/usr/local/bin/docker", "buildx", "build",
        "--platform", platforms,
        "-t", "$registry/pdca-app:$tag",
        "-t", "$registry/pdca-app:latest",
        "--push", ".",
    )
    workingDir = projectDir
    environment("PATH", "/usr/local/bin:/usr/bin:/bin")
}

// Importable GZAC case-definition zips (config/case/<key>/<version>/, see
// gzac/case-definitions/README.md). Output is reproducible so the committed
// zips only change when their content does.
val caseKeys = listOf(
    "inwonerplan",
    "binnenhof-renovatie",
    "intake-werk-participatie",
    "renovatie-intake",
    "instrument-verzoek",
)
val caseZipTasks = caseKeys.map { caseKey ->
    tasks.register<Zip>("caseZip-$caseKey") {
        group = "gzac"
        description = "Build the importable GZAC case-definition zip for '$caseKey'"
        from(layout.projectDirectory.dir("gzac/case-definitions/$caseKey"))
        include("config/**")
        exclude("**/.DS_Store")
        archiveFileName = "$caseKey.zip"
        destinationDirectory = layout.projectDirectory.dir("gzac/case-definitions")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.register("buildGzacZips") {
    group = "gzac"
    description = "Build all importable GZAC case-definition zips"
    dependsOn(caseZipTasks)
}

// ---------------------------------------------------------------------------
// Node toolchain of the frontend and the register plugins (Node 22+). Node is
// taken from -PnodeDir=<dir with bin/node>, else the newest nvm-installed
// Node 22+, else the `node` on PATH.

val nodeBinDir: Provider<String> = providers.gradleProperty("nodeDir")
    .map { File(it, "bin").path }
    .orElse(provider {
        File(System.getProperty("user.home"), ".nvm/versions/node")
            .listFiles { f -> f.isDirectory && f.name.startsWith("v") }
            ?.mapNotNull { dir ->
                val parts = dir.name.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
                if (parts.size == 3 && parts[0] >= 22) parts to dir else null
            }
            ?.maxWithOrNull { a, b ->
                a.first.zip(b.first).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
            }
            ?.second?.resolve("bin")?.path
            ?: ""
    })

fun Exec.useNode(vararg npmArgs: String) {
    val bin = nodeBinDir.get()
    val npm = if (bin.isEmpty()) "npm" else "$bin/npm"
    commandLine(listOf(npm) + npmArgs)
    val path = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
    environment("PATH", if (bin.isEmpty()) path else "$bin${File.pathSeparator}$path")
    doFirst {
        val node = if (bin.isEmpty()) "node" else "$bin/node"
        val version = try {
            ProcessBuilder(node, "--version").start().inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            throw GradleException("Node not found ($node). Install Node 22+ or pass -PnodeDir=<node install dir>.")
        }
        val major = version.removePrefix("v").substringBefore(".").toIntOrNull() ?: 0
        if (major < 22) {
            throw GradleException("This build needs Node 22+, found $version ($node). Pass -PnodeDir=<node install dir>.")
        }
    }
}

// ---------------------------------------------------------------------------
// The React bundles (frontend/). `./gradlew build` installs the frontend's
// dependencies (npm ci, only when package.json or the lockfile changed),
// checks it (frontendCheck: typecheck, lint, tests; part of `check`) and
// builds the bundles into src/main/resources/static/bundles/react before
// processResources, so the jar always carries them. Every step is skipped
// while its inputs are unchanged. -PskipFrontend leaves the frontend out
// altogether: the jar then packages whatever bundles are present.

val skipFrontend = providers.gradleProperty("skipFrontend").map { it != "false" }.getOrElse(false)
val frontendDir = layout.projectDirectory.dir("frontend")
val frontendStamps = layout.buildDirectory.dir("frontend")
val frontendSources = fileTree(frontendDir) {
    include("src/**", "*.json", "*.ts", "*.js")
    exclude("package-lock.json")
}

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    group = "frontend"
    description = "Install the frontend's npm dependencies from its lockfile (npm ci)"
    onlyIf { !skipFrontend }
    workingDir(frontendDir)
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"))
    // npm writes this file on every install; snapshotting all of node_modules would be slow.
    outputs.file(frontendDir.file("node_modules/.package-lock.json"))
    useNode("ci", "--no-audit", "--no-fund")
}

/** A frontend check: an npm script, rerun only when the sources change. */
fun frontendCheckTask(name: String, script: String, what: String) = tasks.register<Exec>(name) {
    group = "frontend"
    description = "Frontend: $what (npm run $script)"
    onlyIf { !skipFrontend }
    dependsOn(frontendInstall)
    workingDir(frontendDir)
    inputs.files(frontendSources)
    inputs.files(frontendDir.file("package-lock.json"))
    val stamp = frontendStamps.map { it.file("$script.ok") }
    outputs.file(stamp)
    useNode("run", script)
    doLast { stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") } }
}

val frontendCheckTasks = listOf(
    frontendCheckTask("frontendTypecheck", "typecheck", "type-check the sources and tests"),
    frontendCheckTask("frontendLint", "lint", "lint the sources"),
    frontendCheckTask("frontendTest", "test", "run the unit tests"),
)

tasks.register("frontendCheck") {
    group = "frontend"
    description = "Type-check, lint and test the frontend"
    dependsOn(frontendCheckTasks)
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    group = "frontend"
    description = "Build the React bundles into src/main/resources/static/bundles/react (vite build)"
    onlyIf { !skipFrontend }
    dependsOn(frontendInstall)
    workingDir(frontendDir)
    inputs.files(frontendSources)
    inputs.files(frontendDir.file("package-lock.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/resources/static/bundles/react"))
    useNode("run", "build")
}

tasks.named("processResources") { dependsOn(frontendBuild) }

// ---------------------------------------------------------------------------
// The register plugins (plugins/<name>): external Valtimo plugins that serve
// the register contracts, on a shared core (plugins/register-core). They form
// one npm workspace (plugins/package.json, one install for all).
// build<Name>Plugin builds one uploadable package
// plugins/<name>/dist/<name>-<version>.zip, buildPlugins builds all of them,
// testPlugins runs the unit tests of the core and the plugins (part of
// `check` when the plugin SDK is present).
// They build against the plugin SDK of a Valtimo checkout (default: a sibling
// `valtimo` directory, override with -PvaltimoDir=...). The workspace depends
// on it through the link build/valtimo-plugin-sdk, which pluginSdkLink points
// at the SDK of that checkout (outside the workspace, so npm leaves the SDK's
// own dependencies alone).

val registerPlugins = mapOf(
    "openplan" to "OpenPlan",
    "openproduct" to "OpenProduct",
    "openklant" to "OpenKlant",
    "objecten" to "Objecten"
)
val valtimoDir = providers.gradleProperty("valtimoDir")
    .map { file(it).canonicalFile }
    .orElse(provider { projectDir.resolve("../valtimo").canonicalFile })
val pluginSdkDir = valtimoDir.map { it.resolve("plugin-host/plugin-sdk") }
val pluginSdkPresent = pluginSdkDir.get().resolve("package.json").isFile

tasks.register<Exec>("pluginSdkBuild") {
    group = "plugin"
    description = "Build the Valtimo plugin SDK the register plugins depend on"
    workingDir(pluginSdkDir)
    inputs.dir(pluginSdkDir.map { it.resolve("src") })
    inputs.file(pluginSdkDir.map { it.resolve("package.json") })
    outputs.dir(pluginSdkDir.map { it.resolve("dist") })
    doFirst {
        if (!pluginSdkDir.get().resolve("package.json").exists()) {
            throw GradleException(
                "Valtimo plugin SDK not found at ${pluginSdkDir.get()}. Check out the valtimo repository next to " +
                    "this one, or pass -PvaltimoDir=<path to the valtimo checkout>."
            )
        }
    }
    useNode("run", "build")
}

val pluginsDir = layout.projectDirectory.dir("plugins")
val pluginSdkLink = tasks.register("pluginSdkLink") {
    group = "plugin"
    description = "Point build/valtimo-plugin-sdk at the plugin SDK of the Valtimo checkout (-PvaltimoDir)"
    doLast {
        val link = layout.buildDirectory.file("valtimo-plugin-sdk").get().asFile.toPath()
        val target = pluginSdkDir.get().toPath()
        val current = if (Files.isSymbolicLink(link)) Files.readSymbolicLink(link) else null
        if (current == target) return@doLast
        if (current == null && Files.exists(link)) {
            throw GradleException("$link exists and is not a link; remove it so it can point at $target")
        }
        Files.deleteIfExists(link)
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, target)
        logger.lifecycle("build/valtimo-plugin-sdk -> $target")
    }
}

val pluginsInstall = tasks.register<Exec>("pluginsInstall") {
    group = "plugin"
    description = "Install the npm dependencies of the register plugins' workspace"
    dependsOn(pluginSdkLink)
    workingDir(pluginsDir)
    inputs.files(pluginsDir.file("package.json"), pluginsDir.file("package-lock.json"))
    inputs.files((registerPlugins.keys + "register-core").map { pluginsDir.file("$it/package.json") })
    // npm writes this file on every install; snapshotting all of node_modules would be slow.
    outputs.file(pluginsDir.file("node_modules/.package-lock.json"))
    useNode("install", "--no-audit", "--no-fund")
}

val pluginCoreSources = fileTree(pluginsDir.dir("register-core")) { include("src/**", "frontend/**", "*.mjs") }

val pluginBuildTasks = registerPlugins.map { (name, taskName) ->
    val pluginDir = pluginsDir.dir(name)
    tasks.register<Exec>("build${taskName}Plugin") {
        group = "plugin"
        description = "Build the uploadable $name plugin package (plugins/$name/dist/$name-<version>.zip)"
        dependsOn("pluginSdkBuild", pluginsInstall)
        workingDir(pluginDir)
        inputs.dir(pluginDir.dir("src"))
        inputs.dir(pluginDir.dir("ui"))
        inputs.files(fileTree(pluginDir.dir("frontend")) { exclude("*.bundle.*") })
        inputs.files(pluginDir.file("manifest.json"), pluginDir.file("package.json"), pluginDir.file("tsconfig.json"))
        inputs.files(pluginCoreSources)
        inputs.dir(pluginSdkDir.map { it.resolve("dist") })
        outputs.dir(pluginDir.dir("dist"))
        // A version bump leaves the previous package behind; only the current one may be loaded.
        doFirst { pluginDir.dir("dist").asFile.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() } }
        useNode("run", "build:pack")
        doLast {
            pluginDir.dir("dist").asFile.listFiles { f -> f.name.endsWith(".zip") }
                ?.forEach { logger.lifecycle("$name plugin package: ${it.path} (${it.length() / 1024} KiB)") }
        }
    }
}

val testPlugins = tasks.register<Exec>("testPlugins") {
    group = "plugin"
    description = "Run the unit tests of the register plugins and their shared core"
    dependsOn("pluginSdkBuild", pluginsInstall)
    workingDir(pluginsDir)
    inputs.files(fileTree(pluginsDir) {
        include("*/src/**", "*/test/**", "*/ui/**", "*/frontend/**", "*/*.json", "register-core/**", "*.json")
        exclude("**/node_modules/**", "**/dist/**", "*/frontend/*.bundle.*")
    })
    inputs.dir(pluginSdkDir.map { it.resolve("dist") })
    val stamp = layout.buildDirectory.file("plugins/test.ok")
    outputs.file(stamp)
    useNode("test")
    doLast { stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") } }
}

tasks.register("buildPlugins") {
    group = "plugin"
    description = "Build the uploadable packages of all register plugins (plugins/*/dist/*.zip)"
    dependsOn(pluginBuildTasks)
}

// `check` covers the frontend (unless -PskipFrontend) and, when a Valtimo
// checkout with the plugin SDK is present, the register plugins; without one
// the plugin tests are skipped with a notice.
tasks.named("check") {
    dependsOn("frontendCheck")
    if (pluginSdkPresent) {
        dependsOn(testPlugins)
    } else {
        doLast {
            logger.lifecycle(
                "Register plugin tests skipped: no Valtimo plugin SDK at ${pluginSdkDir.get()} " +
                    "(check out valtimo next to this repository or pass -PvaltimoDir=...)."
            )
        }
    }
}



// ---------------------------------------------------------------------------
// Local GZAC dev setup (gzac/dev-setup/README.md): configures a running local
// GZAC — the dev app of a Valtimo checkout with its gzac-docker-compose stack —
// for PDCA through GZAC's admin REST API, as the dev administrator. The setup
// is a small Kotlin program (source set devSetup, gzac/dev-setup/src) with
// the dev values in gzac/dev-setup/dev-values.json. gzacDevSetup runs it on
// its own; bootRunWithDocker first prints a checklist of the local GZAC and,
// when something is left to set up, asks whether to run it in the background
// next to the app. The Valtimo checkout is never changed.

val devSetup = sourceSets.create("devSetup")
kotlin.sourceSets.named("devSetup") { kotlin.srcDir("gzac/dev-setup/src") }
dependencies {
    "devSetupImplementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation(devSetup.output)
}
tasks.named("check") { dependsOn(devSetup.classesTaskName) }

// The daemon's PATH may lack the docker CLI (e.g. a daemon started from the
// IDE); processes started from here get the usual install locations.
val toolPath = listOf(
    "/usr/local/bin",
    "/opt/homebrew/bin",
    "${System.getProperty("user.home")}/.docker/bin",
    "/Applications/Docker.app/Contents/Resources/bin",
    System.getenv("PATH") ?: "/usr/bin:/bin",
).joinToString(File.pathSeparator)

/** [name] on [toolPath], or null. */
fun findTool(name: String): String? =
    toolPath.split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.path

val gzacDevUrl = (System.getenv("GZAC_DEV_URL") ?: "http://localhost:8080").trimEnd('/')
val gzacFrontendUrl = "http://localhost:4200"
val devSetupMainClass = "com.ritense.pdca.devsetup.MainKt"
val devSetupJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
val devSetupWaitSeconds = 900

tasks.register<JavaExec>("gzacDevSetup") {
    group = "gzac"
    description = "Configure the local GZAC for PDCA (waits for GZAC and the PDCA app; register plugins, verzoek route, PDCA app, case definitions)"
    dependsOn("buildPlugins")
    classpath = devSetup.runtimeClasspath
    mainClass = devSetupMainClass
    args("--wait=$devSetupWaitSeconds", "--repo=${projectDir.path}")
    environment("PATH", toolPath)
}

/**
 * Runs [command] to its end (at most [timeoutSeconds]); its exit code and
 * output, or null when it did not start or finish. The process gets the
 * build's environment explicitly: the daemon's own may be stale.
 */
fun runQuietly(command: List<String>, timeoutSeconds: Long = 15): Pair<Int, String>? = runCatching {
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    builder.environment().putAll(System.getenv())
    val process = builder.start()
    val output = StringBuilder()
    val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    reader.join(1000)
    process.exitValue() to output.toString()
}.getOrNull()

/**
 * The HTTP status [url] answers with within a few seconds, or null. Every
 * address of the host is tried: a dev server may listen on IPv6 or IPv4 only.
 */
fun httpStatus(url: String): Int? {
    val uri = URI(url)
    val addresses = runCatching { InetAddress.getAllByName(uri.host).toList() }.getOrDefault(emptyList())
    return addresses.firstNotNullOfOrNull { address ->
        runCatching {
            val host = if (address is java.net.Inet6Address) "[${address.hostAddress}]" else address.hostAddress
            val connection = URI(uri.scheme, null, host, uri.port, uri.path, uri.query, null).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 3000
            connection.responseCode
        }.getOrNull()
    }
}

val onMac = "mac" in System.getProperty("os.name").lowercase()

/** Whether this machine resolves host.docker.internal, asked in a fresh process on macOS (no JVM name cache). */
fun hostDockerInternalResolves(): Boolean =
    if (onMac) {
        runQuietly(listOf("/usr/bin/dscacheutil", "-q", "host", "-a", "name", "host.docker.internal"))?.second?.contains("address:") == true
    } else {
        runCatching { InetAddress.getByName("host.docker.internal") }.isSuccess
    }

/**
 * Appends `127.0.0.1 host.docker.internal` to /etc/hosts unless the name is
 * already there, with macOS's administrator password prompt; whether it ran.
 */
fun addHostDockerInternal(): Boolean {
    val shell = "grep -qE '^[^#]*[[:space:]]host\\.docker\\.internal([[:space:]]|$)' /etc/hosts || " +
        "printf '\\n# added by pdca-app dev setup\\n127.0.0.1 host.docker.internal\\n' >> /etc/hosts; " +
        "dscacheutil -flushcache; killall -HUP mDNSResponder || true"
    val script = "do shell script \"${shell.replace("\\", "\\\\").replace("\"", "\\\"")}\" with administrator privileges"
    return runQuietly(listOf("/usr/bin/osascript", "-e", script), timeoutSeconds = 300)?.first == 0
}

/**
 * Whether GZAC holds everything the setup puts in, asked through the setup
 * program's --check (SetupCheck): true, false with what is missing, or null
 * with why it cannot tell.
 */
fun pdcaSetupState(): Pair<Boolean?, String> {
    fun newest(dirs: Iterable<File>) = dirs.flatMap { it.walkTopDown().filter(File::isFile).toList() }.maxOfOrNull { it.lastModified() } ?: 0L
    if (newest(devSetup.output.classesDirs.files) < newest(listOf(file("gzac/dev-setup/src")))) {
        return null to "not checked yet, the setup is compiled first"
    }
    val result = runQuietly(
        listOf(
            devSetupJava.get().executablePath.asFile.path, "-cp", devSetup.runtimeClasspath.asPath,
            devSetupMainClass, "--check", "--repo=${projectDir.path}",
        ),
        timeoutSeconds = 60,
    ) ?: return null to "the check did not finish"
    val lines = result.second.lines()
    fun after(prefix: String) = lines.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    val missing = after("missing: ")
    return when (result.first) {
        0 -> true to ""
        1 -> false to missing.take(3).joinToString(", ") + if (missing.size > 3) " and ${missing.size - 3} more" else ""
        else -> null to (after("unknown: ").firstOrNull() ?: "the check failed")
    }
}

/**
 * Whether bootRunWithDocker sets up the local GZAC in this build: decided by
 * the checklist once the task graph is known, null in any other build.
 */
val gzacDevSetupWanted = AtomicReference<Boolean?>(null)
val gzacDevSetupAnswer = providers.gradleProperty("gzacDevSetup")

val userInput = (project as ProjectInternal).services.get(UserInputHandler::class.java)

/** A yes/no question; without a console Gradle answers the default, no. */
fun askYesNo(question: String): Boolean =
    userInput.askUser { it.askQuestion(question, "n") }.getOrElse("n").trim().lowercase() in setOf("y", "yes")

/**
 * Prints the checklist of the local GZAC and returns whether to set it up for
 * PDCA in this build: asked only when something is left to set up;
 * -PgzacDevSetup=true|false answers upfront (true runs it regardless).
 */
fun gzacChecklist(): Boolean {
    val valtimo = valtimoDir.get()
    if (!valtimo.resolve("backend").isDirectory) return false
    val shown = valtimo.relativeToOrSelf(projectDir).invariantSeparatorsPath
    val log = logger
    fun ok(text: String) = log.lifecycle("  ✓ $text")
    fun notOk(text: String, action: String) {
        log.lifecycle("  ✗ $text")
        log.lifecycle("    → $action")
    }

    log.lifecycle("Local GZAC")
    ok("Valtimo checkout: $shown")

    val docker = findTool("docker")
    if (docker != null && runQuietly(listOf(docker, "info", "--format", "{{.ServerVersion}}"))?.first == 0) {
        ok("Docker is running")
    } else {
        notOk("Docker is not running", "start Docker Desktop")
    }

    if (hostDockerInternalResolves()) {
        ok("host.docker.internal resolves")
    } else {
        log.lifecycle("  ✗ host.docker.internal does not resolve: GZAC follows URLs the containers create with that name (verzoeken, objecttypes)")
        val fixed = onMac && askYesNo("Add host.docker.internal to /etc/hosts now? (macOS asks for your password) [y/n]") &&
            addHostDockerInternal() && hostDockerInternalResolves()
        if (fixed) {
            ok("host.docker.internal resolves")
        } else {
            log.lifecycle(
                "    → Docker Desktop → Settings → General → \"Add the *.docker.internal names to the host's /etc/hosts file\", " +
                    "or: sudo sh -c 'echo \"127.0.0.1 host.docker.internal\" >> /etc/hosts'"
            )
        }
    }

    val gzacUp = httpStatus("$gzacDevUrl/api/v1/ping") == 200
    if (gzacUp) {
        ok("GZAC backend: $gzacDevUrl")
    } else {
        notOk("GZAC backend is not running ($gzacDevUrl)", "cd $shown && ./gradlew :backend:apps:dev:bootRunWithDocker (the setup waits for it)")
    }

    if (httpStatus(gzacFrontendUrl) != null) {
        ok("GZAC frontend: $gzacFrontendUrl")
    } else {
        notOk("GZAC frontend is not running ($gzacFrontendUrl)", "cd $shown/frontend && npm start")
    }

    val given = gzacDevSetupAnswer.orNull?.trim()?.lowercase()
    val (configured, detail) = if (gzacUp) pdcaSetupState() else null to "GZAC not running"
    when (configured) {
        true -> ok("PDCA set up in GZAC")
        false -> notOk(
            "PDCA not fully set up in GZAC: missing $detail",
            when (given) {
                "true" -> "set up in the background next to the app"
                "false" -> "./gradlew gzacDevSetup sets it up"
                else -> "answer y below to set it up"
            },
        )
        null -> log.lifecycle("  ? PDCA set up in GZAC: unknown ($detail)")
    }

    if (given == "true") return true
    if (given == "false") return false
    if (configured == true) {
        log.lifecycle("GZAC is set up for PDCA")
        return false
    }
    return askYesNo("Configure GZAC for PDCA? [y/n]")
}

gradle.taskGraph.whenReady {
    if (hasTask(":bootRunWithDocker")) gzacDevSetupWanted.set(gzacChecklist())
}

// Building the register plugins is only needed when the answer is yes.
listOf("pluginSdkLink", "pluginSdkBuild", "pluginsInstall", "buildPlugins").plus(pluginBuildTasks.map { it.name }).forEach { name ->
    tasks.named(name) {
        onlyIf("the local GZAC is set up in this build") { gzacDevSetupWanted.get() != false }
    }
}

/** Processes started during the build that must not outlive it: stopped when the build ends. */
abstract class BuildScopedProcesses : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val processes = Collections.synchronizedList(mutableListOf<Process>())

    fun start(command: List<String>, environment: Map<String, String>, onLine: (String) -> Unit) {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        processes += process
        Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        processes.filter { it.isAlive }.forEach { it.destroy() }
    }
}

val buildScopedProcesses = gradle.sharedServices.registerIfAbsent("buildScopedProcesses", BuildScopedProcesses::class) {}

// After a yes, the whole setup runs in the background next to the app: it
// waits for GZAC, configures it, waits for the app's health check and adds
// what needs the app. It never holds up or stops the app and ends with the build.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    usesService(buildScopedProcesses)
    doFirst {
        if (gzacDevSetupWanted.get() != true) return@doFirst
        val command = listOf(
            devSetupJava.get().executablePath.asFile.path,
            "-cp", devSetup.runtimeClasspath.asPath,
            devSetupMainClass,
            "--wait=$devSetupWaitSeconds", "--exit-with-parent", "--repo=${projectDir.path}",
        )
        val log = logger
        buildScopedProcesses.get().start(command, mapOf("PATH" to toolPath)) { line -> log.lifecycle(line) }
    }
}
