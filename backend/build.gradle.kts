plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.devbrain"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // SSE 스트리밍

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // pgvector 지원 (hibernate-core는 Spring Boot가 자동 관리)
    implementation("com.pgvector:pgvector:0.1.6")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Swagger (springdoc-openapi)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// .env 파일을 읽어서 환경 변수로 설정
fun loadEnvFile(): Map<String, String> {
    val envFile = file("../.env")
    val envVars = mutableMapOf<String, String>()
    if (envFile.exists()) {
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val (key, value) = trimmed.split("=", limit = 2)
                envVars[key.trim()] = value.trim()
            }
        }
    }
    return envVars
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val envVars = loadEnvFile()
    environment(envVars)
}

