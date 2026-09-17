import java.io.File
import java.net.URI
import org.springframework.boot.gradle.tasks.bundling.BootJar

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
// Boot 3.5.x's BOM pins an older Kotlin; overriding this BOM property keeps the runtime
// stdlib aligned with the 2.1.x compiler (PLAN §3: Kotlin 2.1+).
extra["kotlin.version"] = libs.versions.kotlin.get()
extra["jackson-bom.version"] = libs.versions.jackson.get()
extra["commons-lang3.version"] = libs.versions.commonsLang3.get()
extra["log4j2.version"] = libs.versions.log4j2.get()
extra["netty.version"] = "4.1.136.Final"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Explicit, unqualified verification lane only; never a default repository or Maven-local fallback.
val ownedPgVerificationRepository = providers.gradleProperty("kiraOwnedPgVerificationRepository")
    .orNull?.takeIf { it.isNotBlank() }

repositories {
    if (providers.gradleProperty("kiraUseMavenLocal").orNull == "true") {
        mavenLocal {
            // A local test JAR is not the production owned-driver publication.
            content { excludeModule("me.manga.kira.internal", "postgresql-owned-cut") }
        }
    }
    val ownedPgRepositoryUrl = providers.environmentVariable("KIRA_OWNED_PG_REPOSITORY_URL")
        .orNull?.takeIf { it.isNotBlank() }
    require(ownedPgRepositoryUrl == null || ownedPgVerificationRepository == null) {
        "Select either the private published repository or explicit unqualified verification staging, not both"
    }
    val ownedPgRepositoryUri = if (ownedPgVerificationRepository != null) {
        val staging = File(ownedPgVerificationRepository)
        require(staging.isAbsolute && staging.isDirectory) {
            "kiraOwnedPgVerificationRepository must name an existing absolute run-owned Maven staging directory"
        }
        staging.toURI()
    } else {
        ownedPgRepositoryUrl?.let { configuredUrl ->
            runCatching { URI(configuredUrl) }.getOrElse {
                error("KIRA_OWNED_PG_REPOSITORY_URL must be a valid HTTPS Maven repository URL")
            }.also { repositoryUri ->
                require(
                    repositoryUri.scheme == "https" && repositoryUri.host != null && repositoryUri.userInfo == null &&
                        repositoryUri.query == null && repositoryUri.fragment == null,
                ) {
                    "KIRA_OWNED_PG_REPOSITORY_URL must be HTTPS without embedded credentials, query or fragment"
                }
            }
        }
    }
    if (ownedPgRepositoryUri != null) {
        exclusiveContent {
            forRepository {
                maven(ownedPgRepositoryUri) {
                    name = if (ownedPgVerificationRepository == null) "KiraOwnedPg" else "KiraOwnedPgVerification"
                    if (ownedPgVerificationRepository == null) {
                        credentials {
                            username = providers.environmentVariable("KIRA_PACKAGES_USER").orNull
                            password = providers.environmentVariable("KIRA_PACKAGES_READ_TOKEN").orNull
                        }
                    }
                    metadataSources {
                        mavenPom()
                        ignoreGradleMetadataRedirection()
                    }
                    mavenContent { releasesOnly() }
                }
            }
            filter { includeModule("me.manga.kira.internal", "postgresql-owned-cut") }
        }
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
    mavenCentral {
        content { excludeModule("me.manga.kira.internal", "postgresql-owned-cut") }
    }
}

configurations.configureEach {
    // Both drivers expose org.postgresql.Driver. Never ship stock classes beside the owned cut.
    exclude(group = "org.postgresql", module = "postgresql")
}

dependencies {
    // --- Spring Boot starters (versions from the Boot BOM) ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // pulls spring-security-oauth2-jose (Nimbus)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // --- Migrations ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // --- Kotlin ---
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation(libs.kotlinx.serialization.json)
    implementation("me.manga.kira.source:source-engine:0.1.0")

    // --- Dormant complaint catalog readback; explicit owned URLConnection transport only ---
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // Exact-version SecretBinary reads use the same explicitly owned synchronous transport.
    implementation(libs.aws.sdk.secretsmanager) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // Dormant J-bound data-key operations; same explicit owned synchronous transport.
    implementation(libs.aws.sdk.kms) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.sdk.url.connection.client)

    // --- API docs ---
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // --- Runtime ---
    // Normal runtime dependency, also inherited by tests/bootJar; never a Test.classpath overlay.
    // Registry/publication and resolved-lock verification remain explicit release prerequisites.
    runtimeOnly(libs.postgresql.owned.cut) {
        version { strictly(libs.versions.postgresqlOwnedCut.get()) }
    }
    runtimeOnly(libs.checker.qual) {
        version { strictly(libs.versions.checkerQual.get()) }
    }

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    constraints {
        testImplementation(libs.commons.compress) {
            because("Testcontainers 1.21.4 requests a Commons Compress release with known vulnerabilities")
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

// The Spring dependency-management plugin aligns the project's Kotlin runtime to 2.1.x.
// Detekt 1.23.8 embeds the Kotlin 2.0.21 compiler and must retain that isolated tool runtime.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.21")
            because("detekt 1.23.8 is compiled against Kotlin 2.0.21")
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

if (ownedPgVerificationRepository != null) {
    tasks.named<BootJar>("bootJar") {
        onlyIf("unqualified verification bootJar needs an explicit nonshipping gate") {
            check(providers.gradleProperty("kiraOwnedPgAllowNonShippingBootJar").orNull == "true") {
                "Verification bootJar requires -PkiraOwnedPgAllowNonShippingBootJar=true; output must not be distributed"
            }
            true
        }
        archiveClassifier.set("unqualified-owned-driver-verification")
        manifest.attributes["Kira-Owned-Driver-Provenance"] = "UNQUALIFIED-RUN-OWNED-MAVEN"
    }
    tasks.matching { it.name == "bootBuildImage" || it.name.startsWith("publish") }.configureEach {
        doFirst {
            error("Unqualified owned-driver verification staging is not authorized for distribution")
        }
    }
}
