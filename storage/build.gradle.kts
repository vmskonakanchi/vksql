plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial.snappy:snappy-java:1.1.10.5")
    implementation("com.github.luben:zstd-jni:1.5.5-11")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "512m"
    jvmArgs("--enable-preview")
}
