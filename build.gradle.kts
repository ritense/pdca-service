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
    description = "Start the PDCA app's dependencies via docker compose"
    commandLine("/usr/local/bin/docker", "compose", "up", "-d")
    workingDir = projectDir
    environment("PATH", "/usr/local/bin:/usr/bin:/bin")
}

tasks.register("bootRunWithDocker") {
    group = "application"
    description = "Start docker compose, then run the PDCA app"
    dependsOn("dockerUp")
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

// Importable GZAC case-definition zips (see gzac/case-definitions/README.md).
// The zip entries keep the config/case/<key>/<version>/ layout that the
// Valtimo import service expects; output is reproducible so the committed
// zips only change when their content does.
val caseZipTasks = listOf("inwonerplan", "binnenhof-renovatie").map { caseKey ->
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

tasks.register("buildCaseZips") {
    group = "gzac"
    description = "Build all importable GZAC case-definition zips"
    dependsOn(caseZipTasks)
}
