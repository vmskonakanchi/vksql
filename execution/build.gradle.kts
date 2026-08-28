plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":storage"))
    implementation(project(":parser"))
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
    maxHeapSize = "2g"
    jvmArgs(
        "--enable-preview",
        "-XX:+UseZGC",              // Low-pause garbage collector
        "-XX:+AlwaysPreTouch",      // Pre-touch heap pages (avoid page faults during execution)
        "-XX:-TieredCompilation",   // Skip interpreter, go straight to C2 JIT
        "-XX:CompileThreshold=100", // JIT earlier
        "-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0" // Disable bounds checks for vector ops
    )
}
