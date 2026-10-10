plugins {
    java
    application
}

group = "io.github.rs0325"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
application { mainClass.set("io.github.rs0325.mixinscope.cli.Main") }

dependencies {
    implementation("org.ow2.asm:asm:9.11")
    implementation("org.ow2.asm:asm-tree:9.11")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform(); maxHeapSize = "512m" }
tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release.set(21) }
tasks.withType<AbstractArchiveTask> { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
dependencyLocking { lockAllConfigurations() }
tasks.wrapper {
    gradleVersion = "9.5.0"
    distributionSha256Sum = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
}

tasks.register<JavaExec>("demo") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.rs0325.mixinscope.Demo")
}
tasks.register<JavaExec>("benchmark") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.rs0325.mixinscope.Benchmark")
    maxHeapSize = "512m"
}
