plugins {
    `java-library`
    antlr
}

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-package", "com.vksql.parser.generated")
    outputDirectory = file("${layout.buildDirectory.get()}/generated-src/antlr/main/com/vksql/parser/generated")
}

sourceSets {
    main {
        java {
            srcDir("${layout.buildDirectory.get()}/generated-src/antlr/main")
        }
    }
}
