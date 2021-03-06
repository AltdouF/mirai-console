/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:JvmMultifileClass
@file:JvmName("MiraiConsoleGradlePluginKt")

package net.mamoe.mirai.console.gradle

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.MAIN_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

class MiraiConsoleGradlePlugin : Plugin<Project> {
    companion object {
        internal const val BINTRAY_REPOSITORY_URL = "https://dl.bintray.com/him188moe/mirai"
    }

    private fun KotlinSourceSet.configureSourceSet(project: Project) {
        languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
        dependencies { configureDependencies(project, this@configureSourceSet) }
    }

    private fun Project.configureTarget(target: KotlinTarget) {
        val miraiExtension = project.miraiExtension

        for (compilation in target.compilations) with(compilation) {
            kotlinOptions {
                if (this !is KotlinJvmOptions) return@kotlinOptions
                jvmTarget = miraiExtension.jvmTarget.toString()
                if (!miraiExtension.dontConfigureKotlinJvmDefault) freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
            }
        }
        target.compilations.flatMap { it.allKotlinSourceSets }.forEach { sourceSet ->
            sourceSet.configureSourceSet(project)
        }
    }

    @Suppress("SpellCheckingInspection")
    private fun KotlinDependencyHandler.configureDependencies(project: Project, sourceSet: KotlinSourceSet) {
        val miraiExtension = project.miraiExtension

        if (!miraiExtension.noCore) compileOnly("net.mamoe:mirai-core:${miraiExtension.coreVersion}")
        if (!miraiExtension.noConsole) compileOnly("net.mamoe:mirai-console:${miraiExtension.consoleVersion}")

        if (sourceSet.name.endsWith("test", ignoreCase = true)) {
            if (!miraiExtension.noCore) api("net.mamoe:mirai-core:${miraiExtension.coreVersion}")
            if (!miraiExtension.noConsole) api("net.mamoe:mirai-console:${miraiExtension.consoleVersion}")
            if (!miraiExtension.noTestCoreQQAndroid) api("net.mamoe:mirai-core-qqandroid:${miraiExtension.coreVersion}")
            when (miraiExtension.useTestConsoleFrontEnd) {
                MiraiConsoleFrontEndKind.TERMINAL -> api("net.mamoe:mirai-console-terminal:${miraiExtension.consoleVersion}")
            }
        }
    }

    private fun Project.configureCompileTarget() {
        extensions.findByType(JavaPluginExtension::class.java)?.apply {
            val miraiExtension = miraiExtension
            sourceCompatibility = miraiExtension.jvmTarget
            targetCompatibility = miraiExtension.jvmTarget
        }

        tasks.withType(JavaCompile::class.java) {
            it.options.encoding = "UTF8"
        }
    }

    private fun Project.registerBuildPluginTasks() {
        val miraiExtension = this.miraiExtension

        tasks.findByName("shadowJar")?.enabled = false

        fun registerBuildPluginTask(target: KotlinTarget, isSinglePlatform: Boolean) {
            tasks.create(if (isSinglePlatform) "buildPlugin" else "buildPlugin${target.name.capitalize()}", ShadowJar::class.java).apply shadow@{
                group = "mirai"

                val compilations = target.compilations.filter { it.name == MAIN_COMPILATION_NAME }

                compilations.forEach {
                    dependsOn(it.compileKotlinTask)
                    from(it.output)
                }

                from(project.configurations.getByName("runtimeClasspath").copyRecursive { dependency ->
                    for (excludedDependency in IGNORED_DEPENDENCIES_IN_SHADOW + miraiExtension.excludedDependencies) {
                        if (excludedDependency.group == dependency.group
                            && excludedDependency.name == dependency.name
                        ) return@copyRecursive false
                    }
                    true
                })

                exclude { file ->
                    file.name.endsWith(".sf", ignoreCase = true)
                }

                destinationDirectory.value(project.layout.projectDirectory.dir(project.buildDir.name).dir("mirai"))

                miraiExtension.shadowConfigurations.forEach { it.invoke(this@shadow) }
            }
        }

        val targets = kotlinTargets
        val isSingleTarget = targets.size == 1
        targets.forEach { target ->
            registerBuildPluginTask(target, isSingleTarget)
        }
    }

    override fun apply(target: Project): Unit = with(target) {
        target.extensions.create("mirai", MiraiConsoleExtension::class.java)

        target.plugins.apply(JavaPlugin::class.java)
        target.plugins.apply(ShadowPlugin::class.java)

        target.repositories.maven { it.setUrl(BINTRAY_REPOSITORY_URL) }

        afterEvaluate {
            configureCompileTarget()
            registerBuildPluginTasks()
            kotlinTargets.forEach { configureTarget(it) }
        }
    }
}

internal val Project.miraiExtension: MiraiConsoleExtension
    get() = extensions.findByType(MiraiConsoleExtension::class.java) ?: error("Cannot find MiraiConsoleExtension in project ${this.name}")

internal val Project.kotlinTargets: Collection<KotlinTarget>
    get() {
        val kotlinExtension = extensions.findByType(KotlinProjectExtension::class.java)
            ?: error("Kotlin plugin not applied. Please read https://www.kotlincn.net/docs/reference/using-gradle.html")

        return when (kotlinExtension) {
            is KotlinMultiplatformExtension -> kotlinExtension.targets
            is KotlinSingleTargetExtension -> listOf(kotlinExtension.target)
            else -> error("[MiraiConsole] Internal error: kotlinExtension is neither KotlinMultiplatformExtension nor KotlinSingleTargetExtension")
        }
    }