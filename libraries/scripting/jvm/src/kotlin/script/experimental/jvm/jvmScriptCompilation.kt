/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.jvm

import java.io.File
import kotlin.reflect.KClass
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContext
import kotlin.script.experimental.util.PropertiesCollection

data class JvmDependency(val classpath: List<File>) : ScriptDependency {
    @Suppress("unused")
    constructor(vararg classpathEntries: File) : this(classpathEntries.asList())
}

interface JvmScriptCompilationConfigurationKeys

open class JvmScriptCompilationConfigurationBuilder : PropertiesCollection.Builder(), JvmScriptCompilationConfigurationKeys {
    companion object : JvmScriptCompilationConfigurationKeys
}

fun JvmScriptCompilationConfigurationBuilder.dependenciesFromClassContext(
    contextClass: KClass<*>, vararg libraries: String, wholeClasspath: Boolean = false
) {
    dependenciesFromClassloader(*libraries, classLoader = contextClass.java.classLoader, wholeClasspath = wholeClasspath)
}

fun JvmScriptCompilationConfigurationBuilder.dependenciesFromCurrentContext(vararg libraries: String, wholeClasspath: Boolean = false) {
    dependenciesFromClassloader(*libraries, wholeClasspath = wholeClasspath)
}

fun JvmScriptCompilationConfigurationBuilder.dependenciesFromClassloader(
    vararg libraries: String,
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    wholeClasspath: Boolean = false
) {
    updateClasspath(
        scriptCompilationClasspathFromContext(*libraries, classLoader = classLoader, wholeClasspath = wholeClasspath)
    )
}

fun ScriptCompilationConfiguration.withUpdatedClasspath(classpath: Iterable<File>): ScriptCompilationConfiguration {

    if (classpath.none()) return this

    val knownClasspath = this[ScriptCompilationConfiguration.dependencies]?.flatMapTo(hashSetOf<File>()) {
        (it as? JvmDependency)?.classpath ?: emptyList()
    }
    val newClasspath = classpath.filterNot { knownClasspath?.contains(it) == true }
    if (newClasspath.isEmpty()) return this

    return ScriptCompilationConfiguration(this) {
        dependencies.append(JvmDependency(newClasspath))
    }
}

fun ScriptCompilationConfiguration.Builder.updateClasspath(classpath: Iterable<File>) = updateClasspathImpl(classpath)

fun JvmScriptCompilationConfigurationBuilder.updateClasspath(classpath: Iterable<File>) = updateClasspathImpl(classpath)

private fun PropertiesCollection.Builder.updateClasspathImpl(classpath: Iterable<File>) {
    if (classpath.none()) return

    val knownClasspath = this[ScriptCompilationConfiguration.dependencies]?.flatMapTo(hashSetOf<File>()) {
        (it as? JvmDependency)?.classpath ?: emptyList()
    }
    val newClasspath = classpath.filterNot { knownClasspath?.contains(it) == true }
    if (newClasspath.isEmpty()) return

    ScriptCompilationConfiguration.dependencies.append(JvmDependency(newClasspath))
}

@Deprecated("Unused")
val JvmScriptCompilationConfigurationKeys.javaHome by PropertiesCollection.keyCopy(ScriptingHostConfiguration.jvm.javaHome)

val JvmScriptCompilationConfigurationKeys.jdkHome by PropertiesCollection.keyCopy(ScriptingHostConfiguration.jvm.jdkHome)

@Suppress("unused")
val ScriptCompilationConfigurationKeys.jvm
    get() = JvmScriptCompilationConfigurationBuilder()

