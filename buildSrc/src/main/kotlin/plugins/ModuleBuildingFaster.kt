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
    companion object {
        val SKIP_PARENT_PROJECT_PATH = listOf(
            ":feature",
            ":infra"
        )
        const val CURRENT_DEVELOPING_PROJECT_PATHS_KEY = "currentDevelopingProjectPaths"
    }

    private var artifacts: List<File> = emptyList()
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
            getProperty(CURRENT_DEVELOPING_PROJECT_PATHS_KEY)?.split(",")?: getProjects(target).map { it.path }
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
            (it is ProjectDependency) && isExistAllAppVariantArtifactInMavenLocalRepo(it.dependencyProject) && isNotDevelopedProject(it.dependencyProject)
        }
    }

    private fun convertProjectDependencyToArtifactDependenciesForProject(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
            if (dependency is ProjectDependency && isNotDevelopedProject(dependency.dependencyProject)) {
                println("$project depends on ${dependency.dependencyProject}")
                if (isExistAllAppVariantArtifactInMavenLocalRepo(dependency.dependencyProject)) {
                    println("Start converting project dependency to artifact with ${dependency.dependencyProject} in $project")
                    convertProjectDependencyToArtifactDependencies(
                        project,
                        dependency.dependencyProject
                    )
                }
            }
        }
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

    private fun collectExistArtifacts(rootProjectMavenLocalDir: String) =
        File(rootProjectMavenLocalDir).walkTopDown().filter { it.isFile && it.extension == "aar" }
            .toList()


    private fun isExistAllAppVariantArtifactInMavenLocalRepo(project: Project) =
        appVariantNames.all { variantName ->
            artifacts.any { artifact ->
                artifact.name.contains(
                    "${project.name}-${variantName}-${project.version}",
                    true
                )
            }.apply {
                if (!this) println("artifact ${project.name}-${variantName}-${project.version}.aar is not exists in MavenLocal repository. can't convert to artifact dependency.")
            }
        }

    private fun convertProjectDependencyToArtifactDependencies(
        project: Project,
        dependencyProject: Project
    ) {
        appVariantNames.forEach { appVariantName ->
            project.dependencies.add(
                "${appVariantName}Implementation",
                "${dependencyProject.rootProject.group}:${dependencyProject.name}-$appVariantName:${dependencyProject.version}"
            )
        }
    }


    private fun isNotDevelopedProject(project: Project) = notDevelopedProjects.contains(project)

    private fun isAndroidLibraryProject(project: Project) =
        project.extensions.findByType(LibraryExtension::class.java) != null

    private fun getOutputFile(project: Project, variantName: String): File? {
        return project.extensions.findByType(LibraryExtension::class.java)?.libraryVariants?.first {
            it.name.equals(variantName)
        }?.outputs?.first()?.outputFile
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