plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.cyclonedx)
    jacoco
}

group = "me.manga.kira"
version = "1.0.0"

springBoot {
    // The release jar also contains the one-shot migration CLI. Pin the HTTP application so clean
    // builds and image builds never depend on main-class auto-detection order.
    mainClass.set("me.manga.kira.backend.KiraBackendApplicationKt")
}

// Force the Spring Boot BOM's Kotlin stdlib/reflect to match the compiler version above.
// Keep runtime alignment explicit for the shared Engine ABI even when the BOM agrees.
extra["kotlin.version"] = libs.versions.kotlin.get()
// The shared Engine's generated serializers require its matching runtime, including JVM/core.
// An explicit JSON root dependency alone does not override Boot's imported serialization BOM.
extra["kotlin-serialization.version"] = libs.versions.kotlinxSerialization.get()
// Boot 4 manages Jackson 3 separately; preserve the supported Jackson 2 API/parser contract.
extra["jackson-2-bom.version"] = libs.versions.jackson.get()
extra["commons-lang3.version"] = libs.versions.commonsLang3.get()
extra["log4j2.version"] = libs.versions.log4j2.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    if (providers.gradleProperty("kiraUseMavenLocal").orNull == "true") {
        mavenLocal()
    }
    maven("https://maven.pkg.github.com/kira-manga/kira-source-engine") {
        name = "KiraSourceEngine"
        credentials {
            username = providers.environmentVariable("KIRA_PACKAGES_USER")
                .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                .orNull
            password = providers.environmentVariable("KIRA_PACKAGES_READ_TOKEN")
                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                .orNull
        }
        content { includeGroup("me.manga.kira.source") }
    }
    mavenCentral()
}

dependencies {
    // --- Spring Boot starters (versions from the Boot BOM) ---
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server") // pulls spring-security-oauth2-jose (Nimbus)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // --- Migrations (Boot 4 auto-configuration is an explicit module) ---
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // --- Supported Jackson 2 transition: preserve existing strict parsers and API DTOs ---
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // --- Kotlin ---
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation(libs.kotlinx.serialization.json)
    implementation("me.manga.kira.source:source-engine:0.2.6")

    // --- API docs ---
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // --- Runtime ---
    runtimeOnly("org.postgresql:postgresql")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("me.manga.kira.source:source-testkit:0.2.6")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    constraints {
        testImplementation(libs.commons.compress) {
            because("retain the verified security floor for test archive handling")
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// JPA entities must be `open` for Hibernate proxying; kotlin-jpa only adds no-arg ctors.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

dependencyLocking {
    lockAllConfigurations()
}

ktlint {
    version.set("1.8.0")
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/build/**")
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("config/detekt/detekt.yml"))
}

// Application BOM upgrades must not change the already-selected static-analysis tool runtimes.
// Detekt 1.23.8 retains Kotlin 2.0.21; ktlint retains Kotlin 2.1.21. Neither consumes the Engine.
val kotlinAnalysisConfigurations = setOf("detekt", "ktlint", "ktlintBaselineReporter", "ktlintReporter", "ktlintRuleset")
configurations.matching { it.name in kotlinAnalysisConfigurations }.configureEach {
    val toolKotlinVersion = if (name == "detekt") "2.0.21" else "2.1.21"
    resolutionStrategy.eachDependency {
        when {
            requested.group == "org.jetbrains.kotlin" -> {
                useVersion(toolKotlinVersion)
                because("preserve the isolated static-analysis Kotlin runtime")
            }

            requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization-") -> {
                useVersion("1.6.3")
                because("preserve the isolated static-analysis serialization runtime")
            }
        }
    }
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("ktlintCheck", "detekt", tasks.jacocoTestCoverageVerification)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}
