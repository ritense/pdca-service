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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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

tasks.register("bootRunWithDocker") {
    group = "application"
    description = "Start docker compose, open the register admins, then run the PDCA app"
    dependsOn("dockerUp", "openRegisterAdmins")
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

// Importable GZAC zips (see gzac/case-definitions/README.md): case definitions
// (config/case/<key>/<version>/) and building blocks
// (config/building-block/<key>/<version>/). Case zips bundle the building
// blocks their building-block-links reference, so one import per case zip
// suffices: GZAC imports bundled building blocks first and skips any that
// already exist. Output is reproducible so the committed zips only change
// when their content does.
val caseZipBouwblokken = mapOf(
    "inwonerplan" to listOf("pdca-actie", "jobcoaching-aanvragen"),
    "binnenhof-renovatie" to listOf("pdca-actie"),
    "intake-werk-participatie" to emptyList(),
    "renovatie-intake" to emptyList(),
    "werkfit-aanvraag" to emptyList(),
)
val caseZipTasks = caseZipBouwblokken.map { (caseKey, bouwblokken) ->
    tasks.register<Zip>("caseZip-$caseKey") {
        group = "gzac"
        description = "Build the importable GZAC case-definition zip for '$caseKey'"
        from(layout.projectDirectory.dir("gzac/case-definitions/$caseKey"))
        bouwblokken.forEach { bouwblokKey ->
            from(layout.projectDirectory.dir("gzac/bouwblokken/$bouwblokKey"))
        }
        include("config/**")
        exclude("**/.DS_Store")
        archiveFileName = "$caseKey.zip"
        destinationDirectory = layout.projectDirectory.dir("gzac/case-definitions")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val bouwblokZipTasks = listOf(
    "pdca-actie", "jobcoaching-aanvragen",
).map { bouwblokKey ->
    tasks.register<Zip>("bouwblokZip-$bouwblokKey") {
        group = "gzac"
        description = "Build the importable GZAC building-block zip for '$bouwblokKey'"
        from(layout.projectDirectory.dir("gzac/bouwblokken/$bouwblokKey"))
        include("config/**")
        exclude("**/.DS_Store")
        archiveFileName = "$bouwblokKey.zip"
        destinationDirectory = layout.projectDirectory.dir("gzac/bouwblokken")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.register("buildGzacZips") {
    group = "gzac"
    description = "Build all importable GZAC zips (case definitions + building blocks)"
    dependsOn(caseZipTasks + bouwblokZipTasks)
}

// ---------------------------------------------------------------------------
// The Open Plan external plugin (plugins/openplan): one task builds the
// uploadable package dist/openplan-<version>.zip. It needs Node >= 22 and the
// Valtimo checkout whose plugin SDK the plugin depends on (default: a
// sibling `valtimo` directory, override with -PvaltimoDir=...). Node is taken
// from -PnodeDir=<dir with bin/node>, else the newest nvm-installed Node 22+,
// else the `node` on PATH.

val openPlanPluginDir = layout.projectDirectory.dir("plugins/openplan")
val valtimoDir = providers.gradleProperty("valtimoDir")
    .map { file(it) }
    .orElse(provider { projectDir.resolve("../valtimo").canonicalFile })
val pluginSdkDir = valtimoDir.map { it.resolve("plugin-host/plugin-sdk") }

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
            throw GradleException("The plugin build needs Node 22+, found $version ($node). Pass -PnodeDir=<node install dir>.")
        }
    }
}

tasks.register<Exec>("openPlanPluginSdkBuild") {
    group = "plugin"
    description = "Build the Valtimo plugin SDK the Open Plan plugin depends on"
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

tasks.register<Exec>("openPlanPluginInstall") {
    group = "plugin"
    description = "Install the Open Plan plugin's npm dependencies"
    workingDir(openPlanPluginDir)
    inputs.files(openPlanPluginDir.file("package.json"), openPlanPluginDir.file("package-lock.json"))
    outputs.dir(openPlanPluginDir.dir("node_modules"))
    useNode("install", "--no-audit", "--no-fund")
}

tasks.register<Exec>("buildOpenPlanPlugin") {
    group = "plugin"
    description = "Build the uploadable Open Plan plugin package (plugins/openplan/dist/openplan-<version>.zip)"
    dependsOn("openPlanPluginSdkBuild", "openPlanPluginInstall")
    workingDir(openPlanPluginDir)
    inputs.dir(openPlanPluginDir.dir("src"))
    inputs.files(fileTree(openPlanPluginDir.dir("frontend")) { exclude("*.bundle.*") })
    inputs.files(openPlanPluginDir.file("manifest.json"), openPlanPluginDir.file("tsconfig.json"))
    inputs.dir(pluginSdkDir.map { it.resolve("dist") })
    outputs.dir(openPlanPluginDir.dir("dist"))
    useNode("run", "build:pack")
    doLast {
        openPlanPluginDir.dir("dist").asFile.listFiles { f -> f.name.endsWith(".zip") }
            ?.forEach { logger.lifecycle("Open Plan plugin package: ${it.path}") }
    }
}
