plugins {
    java
    application
}

group = "io.github.rs0325"
version = file("VERSION").readText().trim()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { content { includeGroup("net.fabricmc") } }
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
application { mainClass.set("io.github.rs0325.mixinscope.cli.Main") }
tasks.processResources { from("VERSION") { rename { "mixinscope-version.txt" } } }
distributions.main {
    contents {
        from("LICENSE", "README.md", "CHANGELOG.md", "THIRD-PARTY.md", "VERSION")
        from("docs") { into("docs") }
        from("licenses") { into("licenses") }
    }
}

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
    testImplementation("net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7")
    testImplementation("io.github.llamalad7:mixinextras-common:0.4.1")
    testImplementation("org.ow2.asm:asm-commons:9.11")
    testImplementation("org.ow2.asm:asm-util:9.11")
}

// Own, compiled fixtures and a Mixin runtime harness are isolated from the shipped CLI.
val runtimeFixture = sourceSets.create("runtimeFixture") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[runtimeFixture.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[runtimeFixture.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.named<JavaCompile>(runtimeFixture.compileJavaTaskName) { options.compilerArgs.add("-proc:none") }
tasks.named<JavaCompile>("compileTestJava") { options.compilerArgs.add("-proc:none") }

tasks.test {
    useJUnitPlatform(); maxHeapSize = "512m"
    dependsOn(runtimeFixture.classesTaskName)
    systemProperty("mixinscope.fixtureClasses", runtimeFixture.output.classesDirs.asPath)
    outputs.dir(layout.buildDirectory.dir("runtime-fixture/static"))
}
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
tasks.register<JavaExec>("fixtureIntegration") {
    dependsOn(runtimeFixture.classesTaskName)
    classpath = runtimeFixture.runtimeClasspath
    mainClass.set("io.github.rs0325.mixinscope.fixture.FixtureIntegration")
    maxHeapSize = "512m"
}
