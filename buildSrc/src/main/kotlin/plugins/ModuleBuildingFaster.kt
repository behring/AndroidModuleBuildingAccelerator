package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.get
import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.configurationcache.extensions.capitalized
import java.io.File

class ModuleBuildingFaster : Plugin<Project> {

    data class Artifact(
        val projectName: String,
        val buildVariant: String,
        val version: String,
        val file: File
    )

    companion object {
        const val IS_COMPLETELY_MATCH_APP_VARIANTS = true
        const val CURRENT_DEVELOPING_PROJECT_PATHS_KEY = "currentDevelopingProjectPaths"
        val SKIP_PARENT_PROJECT_PATH = listOf(
            ":feature",
            ":infra"
        )
    }

    private var artifacts: List<Artifact> = emptyList()
    private var appVariantNames: List<String> = emptyList()
    private var notDevelopedProjects: List<Project> = emptyList()

    override fun apply(target: Project) {
        target.gradle.addListener(TimingsListener())
        notDevelopedProjects = getNotDevelopedProjects(target)
        addMavenPublishPluginToSubProject(target)
        convertDependencyConfiguration(target)
    }


    private fun setEmptySourceSetsForNotDevelopedProject(project: Project) {
        if (notDevelopedProjects.contains(project) && isAndroidLibraryProject(project)) {
            project.tasks.forEach { it.enabled = false }
            println("disable tasks for ${project.path}")
        }
    }

    private fun getNotDevelopedProjects(target: Project): List<Project> {
        return getProjects(target).filterNot {
            getCurrentDevelopingProjectPaths(target).contains(
                it.path
            )
        }
    }

    private fun getCurrentDevelopingProjectPaths(target: Project): List<String> {
        return java.util.Properties().run {
            load(File(target.rootDir.absolutePath + "/local.properties").inputStream())
            getProperty(CURRENT_DEVELOPING_PROJECT_PATHS_KEY)?.split(",")
                ?: getProjects(target).map { it.path }
        }
    }

    private fun addMavenPublishPluginToSubProject(target: Project) {
        getProjects(target).forEach { it.plugins.apply("maven-publish") }
    }

    private fun convertDependencyConfiguration(target: Project) {
        target.gradle.projectsEvaluated {
            val rootProjectMavenLocalDir = getMavenLocalDirForRootProject(target)
            println("Artifacts directory: $rootProjectMavenLocalDir")
            artifacts = collectExistArtifacts(rootProjectMavenLocalDir)

            getProjects(target).forEach { project ->
                if (isAppProject(project)) {
                    appVariantNames = getAppVariantNames(project)
                    convertProjectDependencyToArtifactDependenciesForProject(project)
                    removeProjectDependencies(project)
                } else if (isAndroidLibraryProject(project)) {
                    if (appVariantNames.isEmpty()) println("No variants information collected.")
                    appVariantNames.forEach { variantName ->
                        configDependencyTaskForMavenPublishTasks(project, variantName)
                        configMavenPublishPluginForLibraryWithAppVariant(project, variantName)
                    }

                    if (!isNotDevelopedProject(project)) {
                        convertProjectDependencyToArtifactDependenciesForProject(project)
                        removeProjectDependencies(project)
                    }

                    setEmptySourceSetsForNotDevelopedProject(project)

                } else {
                    println("This project is not a app or android module. project name: ${project.name}")
                }
            }
        }
    }

    private fun getProjects(target: Project) =
        target.subprojects.filterNot { SKIP_PARENT_PROJECT_PATH.contains(it.path) }

    private fun removeProjectDependencies(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.removeIf {
            (it is ProjectDependency) && isExistArtifacts(it.dependencyProject) && isNotDevelopedProject(
                it.dependencyProject
            )
        }
    }

    private fun convertProjectDependencyToArtifactDependenciesForProject(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
            if (dependency is ProjectDependency && isNotDevelopedProject(dependency.dependencyProject)) {
                println("$project depends on ${dependency.dependencyProject}")
                if (IS_COMPLETELY_MATCH_APP_VARIANTS) {
                    convertDependencyConfigurationBasedOnAppVariants(project, dependency)
                } else {
                    convertDependencyConfigurationBasedOnExistArtifacts(project, dependency)
                }
            }
        }
    }

    private fun convertDependencyConfigurationBasedOnExistArtifacts(
        project: Project,
        dependency: ProjectDependency
    ) {
        if (isExistArtifacts(dependency.dependencyProject)) {
            convertProjectDependencyToArtifactDependenciesWithExistingArtifacts(
                project,
                dependency.dependencyProject
            )
        } else println("Project ${dependency.dependencyProject} have not corresponding artifacts，please generate them with publish task.")
    }

    private fun convertDependencyConfigurationBasedOnAppVariants(
        project: Project,
        dependency: ProjectDependency
    ) {
        if (isExistAllAppVariantArtifacts(dependency.dependencyProject)) {
            convertProjectDependencyToArtifactDependenciesWithAppVariants(
                project,
                dependency.dependencyProject
            )
        } else println("Project ${dependency.dependencyProject} have not corresponding artifacts，please generate them with publish task.")
    }

    private fun getMavenLocalDirForRootProject(target: Project): String {
        return "${System.getProperties()["user.home"]}/.m2/repository/${target.groupPath()}"
    }

    private fun Project.groupPath() = group.toString().replace(".", "/")

    private fun configMavenPublishPluginForLibraryWithAppVariant(
        project: Project,
        variantName: String
    ) {
        project.extensions.getByType(PublishingExtension::class.java)
            .publications.maybeCreate(
                project.name + variantName.capitalized(),
                MavenPublication::class.java
            ).run {
                groupId = project.rootProject.group.toString()
                artifactId = "${project.name}-$variantName"
                version = project.version.toString()
                getOutputFile(project, variantName)?.let {
                    artifact(it)
                }
            }
    }

    private fun configDependencyTaskForMavenPublishTasks(project: Project, variantName: String) {
        project.tasks.whenTaskAdded {
            if (name.startsWith("publish${project.name.capitalized()}${variantName.capitalized()}PublicationTo")) {
                dependsOn("${project.path}:assemble${variantName.capitalized()}")
            }
        }
    }

    private fun collectExistArtifacts(rootProjectMavenLocalDir: String): List<Artifact> {
        val artifacts = mutableListOf<Artifact>()
        File(rootProjectMavenLocalDir).walkTopDown().filter { it.isFile && it.extension == "aar" }
            .forEach {
                // moduleName-buildVariant-version.aar
                val moduleInfo = it.nameWithoutExtension.split("-")
                artifacts.add(
                    Artifact(
                        projectName = moduleInfo[0],
                        buildVariant = moduleInfo[1],
                        version = moduleInfo[2],
                        file = it
                    )
                )
            }
        return artifacts
    }


    private fun getArtifacts(project: Project) = artifacts.filter { it.projectName == project.name }

    private fun isExistArtifacts(project: Project) = getArtifacts(project).isNotEmpty()

    private fun isExistAllAppVariantArtifacts(project: Project) =
        appVariantNames.all { variantName ->
            artifacts.any { artifact ->
                artifact.run {
                    projectName == project.name && buildVariant == variantName && version == project.version
                }
            }.apply {
                if (!this) println("artifact ${project.name}-${variantName}-${project.version}.aar is not exists in MavenLocal repository. can't convert to artifact dependency.")
            }
        }

    private fun convertProjectDependencyToArtifactDependenciesWithAppVariants(
        project: Project,
        dependencyProject: Project
    ) {
        appVariantNames.forEach { appVariantName ->
            println(
                "[Converting based on all variants] Convert project" +
                        " dependency to artifact with ${appVariantName}Implementation(${dependencyProject.rootProject.group}:${dependencyProject.name}-$appVariantName:${dependencyProject.version}) for $project"
            )

            project.dependencies.add(
                "${appVariantName}Implementation",
                "${dependencyProject.rootProject.group}:${dependencyProject.name}-$appVariantName:${dependencyProject.version}"
            )
        }
    }

    // https://developer.android.com/studio/build#sourcesets
    private fun convertProjectDependencyToArtifactDependenciesWithExistingArtifacts(
        project: Project,
        dependencyProject: Project
    ) {
        getArtifacts(dependencyProject).forEach {
            println(
                "[Converting based on existing artifacts] Convert project" +
                        " dependency to artifact with ${it.buildVariant}Implementation(${dependencyProject.rootProject.group}:${dependencyProject.name}-${it.buildVariant}:${it.version}) for $project"
            )
            project.dependencies.add(
                "${it.buildVariant}Implementation",
                "${dependencyProject.rootProject.group}:${dependencyProject.name}-${it.buildVariant}:${it.version}"
            )
        }
    }

    private fun isNotDevelopedProject(project: Project) = notDevelopedProjects.contains(project)

    private fun isAndroidLibraryProject(project: Project) =
        project.extensions.findByType(LibraryExtension::class.java) != null

    private fun getOutputFile(project: Project, variantName: String): File? {
        return try {
            project.extensions.findByType(LibraryExtension::class.java)?.libraryVariants?.first {
                it.name.equals(variantName)
            }?.outputs?.first()?.outputFile
        } catch (e: NoSuchElementException) {
            null
        }
    }

    private fun isAppProject(project: Project) =
        project.extensions.findByType(AppExtension::class.java) != null

    private fun getAppVariantNames(project: Project) =
        project.extensions.findByType(AppExtension::class.java)!!.applicationVariants.map { variant ->
            variant.name
        }

    private fun getImplementationConfiguration(project: Project): Configuration? {
        return try {
            project.configurations["implementation"]
        } catch (ignore: Exception) {
            null
        }
    }
}