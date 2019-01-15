import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":kotlin-stdlib"))
    compile(project(":core:util.runtime"))
    compile(project(":compiler:backend"))
    compile(project(":compiler:frontend"))
    compile(project(":compiler:frontend.java"))
    compile(project(":compiler:light-classes"))

    Platform[181].orHigher {
        // BEWARE: Uast should not depend on IDEA.
        compileOnly(intellijCoreDep()) { includeJars("intellij-core") }
        compileOnly(intellijDep()) { includeJars("java-api", "java-impl", "asm-all", rootProject = rootProject) }
    }

    Platform[173].orLower {
        compile(project(":idea:idea-core"))
        compileOnly(intellijDep()) { includeJars("openapi", "idea", "util", "extensions", "asm-all", rootProject = rootProject) }
    }

    testCompile(project(":kotlin-test:kotlin-test-jvm"))
    testCompile(projectTests(":compiler:tests-common"))
    testCompile(commonDep("junit:junit"))
    testCompile(project(":compiler:util"))
    testCompile(project(":compiler:cli"))
    testCompile(projectTests(":idea:idea-test-framework"))

    Platform[181].orHigher {
        testCompileOnly(intellijDep()) { includeJars("java-api", "java-impl") }
    }

    Platform[173].orLower {
        testCompileOnly(intellijDep()) { includeJars("idea_rt") }
    }

    testCompile(project(":idea:idea-native")) { isTransitive = false }
    testCompile(project(":idea:idea-gradle-native")) { isTransitive = false }

    testRuntime(project(":kotlin-native:kotlin-native-library-reader")) { isTransitive = false }
    testRuntime(project(":kotlin-native:kotlin-native-utils")) { isTransitive = false }
    testRuntime(project(":kotlin-reflect"))
    testRuntime(project(":idea:idea-android"))
    testRuntime(project(":idea:idea-gradle"))
    testRuntime(project(":plugins:kapt3-idea")) { isTransitive = false }
    testRuntime(project(":sam-with-receiver-ide-plugin"))
    testRuntime(project(":allopen-ide-plugin"))
    testRuntime(project(":noarg-ide-plugin"))
    testRuntime(project(":kotlin-scripting-idea"))
    testRuntime(project(":plugins:android-extensions-ide"))
    testRuntime(project(":plugins:kapt3-idea"))
    testRuntime(project(":kotlinx-serialization-ide-plugin"))
    testRuntime(intellijDep())
    testRuntime(intellijPluginDep("junit"))
    testRuntime(intellijPluginDep("gradle"))
    testRuntime(intellijPluginDep("Groovy"))
    testRuntime(intellijPluginDep("properties"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

testsJar {}

projectTest {
    workingDir = rootDir
}

if (Platform.P191.orHigher()) {
    tasks.withType<KotlinCompile> {
        kotlinOptions.freeCompilerArgs += "-Xjvm-default=compatibility"
    }
}
