// openPMS backend: Spring Boot 4 + Java 21, jOOQ + Flyway on PostgreSQL 17 (replica/architecture.md §1).
// Build and run through scripts/dev.sh (everything runs in Podman containers).
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jooq.jooq-codegen-gradle") version "3.21.7"
}

group = "com.openpms"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// jOOQ classes are generated from the migrated dev database and checked in (src/main/generated), so a
// normal build needs no database. Regenerate after a schema change: scripts/dev.sh gradle jooqCodegen
sourceSets["main"].java.srcDir("src/main/generated")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.kagkarlsson:db-scheduler:16.12.0")
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20260924.2")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("org.apache.tika:tika-core:3.3.2")
    runtimeOnly("org.postgresql:postgresql")

    jooqCodegen("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = System.getenv("OPENPMS_DB_URL") ?: "jdbc:postgresql://localhost:55432/openpms"
            user = System.getenv("OPENPMS_DB_USER") ?: "openpms"
            password = System.getenv("OPENPMS_DB_PASSWORD") ?: ""
        }
        generator {
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
                // library tables are not ours to query
                excludes = "flyway_schema_history|spring_session.*|scheduled_tasks|set_updated_at|citext.*|gtrgm.*|gin_.*|similarity.*|word_similarity.*|strict_word_similarity.*|show_.*|set_limit|regexp_.*|replace|split_part|strpos|translate|texticlike|texticnlike|texticregexeq|texticregexne|textic.*|max|min|crypt|gen_salt|pgp_.*|.*_trgm.*"
                includeRoutines = false
                includeSequences = true
                forcedTypes {
                    forcedType {
                        name = "VARCHAR"
                        includeTypes = "citext"
                    }
                }
            }
            generate {
                isJavaTimeTypes = true
                isPojos = false
                isDaos = false
                isRecords = true
                isFluentSetters = true
            }
            target {
                packageName = "com.openpms.db"
                directory = "src/main/generated"
            }
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
