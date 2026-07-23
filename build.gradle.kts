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
extra["netty.version"] = "4.1.136.Final"
extra["postgresql.version"] = "42.7.12"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
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
    implementation(libs.kotlinx.serialization.json)

    // --- API docs ---
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // --- Runtime ---
    runtimeOnly("org.postgresql:postgresql")

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
