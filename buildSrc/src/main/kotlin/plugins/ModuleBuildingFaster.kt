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
import java.util.*
import kotlin.NoSuchElementException

class ModuleBuildingFaster : Plugin<Project> {

    data class Artifact(
        val projectName: String,
        val buildVariant: String,
        val version: String,
        val file: File
    )

    companion object {
        // All project dependencies will be converted to artifacts dependencies when this value is true.
        // Please make sure you have published all artifacts for relevant projects.
        const val PLUGIN_ENABLE_SWITCH_KEY = "buildingFaster.enable"

        // This property is used to control which modules will be as a project dependency.
        // if the value is null, all android libraries will be disabled for all tasks.
        const val WORKSPACE = "buildingFaster.workspace"

        const val IS_COMPLETELY_MATCH_APP_VARIANTS = false
        const val SEPARATOR = "-"
        val SKIP_PARENT_PROJECT_PATH = listOf(
            ":feature",
            ":infra"
        )
    }

    private lateinit var configProperties: Properties
    private var artifacts: List<Artifact> = emptyList()
    private var appVariantNames: List<String> = emptyList()
    private var nonWorkspaceProjects: List<Project> = emptyList()

    override fun apply(target: Project) {
        target.gradle.addListener(TimingsListener())
        configProperties = loadConfigProperties(target)
        if (!isEnablePlugin()) return
        nonWorkspaceProjects = getNonWorkspaceProjects(target)
        addMavenPublishPluginToSubProject(target)

        getProjects(target).forEach { project ->
            if (isAndroidLibraryProject(project)) {
                project.afterEvaluate {
                    getLibraryExtension(project).publishing.run{
                        listOf("debug", "release").forEach { libraryVariant ->
                            singleVariant(libraryVariant)
                        }
                    }
                }
            }
        }

        convertDependencyConfiguration(target)
    }

    private fun loadConfigProperties(target: Project) =
        Properties().apply {
            load(File(target.rootDir.absolutePath + "/local.properties").inputStream())
        }

    private fun getNonWorkspaceProjects(target: Project): List<Project> {
        return getProjects(target).filterNot {
            getWorkspaceModulePaths().contains(
                it.path
            )
        }
    }

    private fun getWorkspaceModulePaths() =
        configProperties.getProperty(WORKSPACE)?.split(",")
            ?: emptyList()

    private fun isEnablePlugin() =
        (configProperties.getProperty(PLUGIN_ENABLE_SWITCH_KEY) ?: "false").toBoolean()

    private fun tryDisableAllTasksForNonWorkspaceProject(project: Project) {
        if (!isExistWorkspace(project) && isAndroidLibraryProject(project) && isExistArtifacts(
                project
            )
        ) {
            project.tasks.forEach { it.enabled = false }
            println("disable tasks for ${project.path}")
        }
    }

    private fun addMavenPublishPluginToSubProject(target: Project) {
        getProjects(target).forEach { it.plugins.apply("maven-publish") }
    }

    private fun convertDependencyConfiguration(target: Project) {
        val rootProjectMavenLocalDir = getMavenLocalDirForRootProject(target)
        println("Artifacts directory: $rootProjectMavenLocalDir")
        artifacts = collectExistArtifacts(rootProjectMavenLocalDir)

        target.gradle.projectsEvaluated {
            getProjects(target).forEach { project ->
                if (isAppProject(project)) {
                    appVariantNames = getAppVariants(project)
                    convertProjectDependencyToArtifactDependenciesForProject(project)
                    removeProjectDependencies(project)
                } else if (isAndroidLibraryProject(project)) {
                    if (appVariantNames.isEmpty()) println("No variants information collected.")

                    configDependencyTaskForMavenPublishTasks(project)
                    configMavenPublishPluginForLibraryWithVariant(project)
                    convertProjectDependencyToArtifactDependenciesForProject(project)
                    removeProjectDependencies(project)

                    tryDisableAllTasksForNonWorkspaceProject(project)

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
            (it is ProjectDependency) && isExistArtifacts(it.dependencyProject) && !isExistWorkspace(
                it.dependencyProject
            )
        }
    }

    private fun convertProjectDependencyToArtifactDependenciesForProject(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
            if (dependency is ProjectDependency && !isExistWorkspace(dependency.dependencyProject)) {
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

    private fun configMavenPublishPluginForLibraryWithVariant(project: Project) {
        getAndroidLibraryVariants(project).forEach { libraryVariant ->
            val publishingExtension = project.extensions.getByType(PublishingExtension::class.java)
            val publications = publishingExtension.publications
            if (publications.findByName(libraryVariant) != null) {
                println("Publication $libraryVariant has been created for $project")
                return
            }

            publications.create(
                project.path.convertToUniqueProjectName() + libraryVariant.capitalized(),
                MavenPublication::class.java
            ) {
                val component = project.components.findByName(libraryVariant)
                if (component != null) {
                    from(project.components.findByName(libraryVariant))
                    groupId = project.rootProject.group.toString()
                    artifactId =
                        "${project.path.convertToUniqueProjectName()}$SEPARATOR$libraryVariant"
                    version = project.version.toString()
                } else {
                    println("Can not obtain component $libraryVariant from $project, config publication failed.")
                }
            }
        }
    }

    private fun String.convertToUniqueProjectName() =
        split(":").joinToString("") { it.convertToCamelNaming() }.decapitalize()

    private fun String.convertToCamelNaming() =
        split(SEPARATOR).joinToString("") { it.toLowerCase(Locale.ROOT).capitalized() }

    private fun configDependencyTaskForMavenPublishTasks(project: Project) {
        getAndroidLibraryVariants(project).forEach { libraryVariant ->
            project.tasks.whenTaskAdded {
                val assembleTaskPath = "${project.path}:assemble${libraryVariant.capitalized()}"
                val assembleTask = project.tasks.findByPath(assembleTaskPath)
                if (assembleTask == null) {
                    println("$assembleTaskPath is not exist. can not publish the corresponding artifact.")
                    return@whenTaskAdded
                }

                if (name.startsWith(
                        "publish${
                            project.path.convertToUniqueProjectName().capitalized()
                        }${libraryVariant.capitalized()}PublicationTo"
                    )
                ) {
                    dependsOn(assembleTask)
                }
            }
        }
    }

    private fun collectExistArtifacts(rootProjectMavenLocalDir: String): List<Artifact> {
        val artifacts = mutableListOf<Artifact>()
        File(rootProjectMavenLocalDir).walkTopDown().filter { it.isFile && it.extension == "aar" }
            .forEach {
                // moduleName-buildVariant-version.aar
                val moduleInfo = it.nameWithoutExtension.split(SEPARATOR, limit = 3)
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


    private fun getArtifacts(project: Project) =
        artifacts.filter { it.projectName == project.path.convertToUniqueProjectName() }

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
                        " dependency to artifact with ${appVariantName}Implementation(${dependencyProject.rootProject.group}:${dependencyProject.path.convertToUniqueProjectName()}-$appVariantName:${dependencyProject.version}) for $project"
            )

            project.dependencies.add(
                "${appVariantName}Implementation",
                "${dependencyProject.rootProject.group}:${dependencyProject.path.convertToUniqueProjectName()}-$appVariantName:${dependencyProject.version}"
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
                        " dependency to artifact with ${it.buildVariant}Implementation(${dependencyProject.rootProject.group}:${dependencyProject.path.convertToUniqueProjectName()}-${it.buildVariant}:${it.version}) for $project"
            )
            project.dependencies.add(
                "${it.buildVariant}Implementation",
                "${dependencyProject.rootProject.group}:${it.projectName}-${it.buildVariant}:${it.version}"
            )
        }
    }

    private fun isExistWorkspace(project: Project) = !nonWorkspaceProjects.contains(project)

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

    private fun getAppVariants(project: Project) =
        project.extensions.findByType(AppExtension::class.java)!!.applicationVariants.map { variant ->
            variant.name
        }

    private fun getLibraryExtension(project: Project): LibraryExtension {
        return project.extensions.findByType(LibraryExtension::class.java)!!
    }

    private fun getAndroidLibraryVariants(project: Project) =
        getLibraryExtension(project).libraryVariants.map { variant ->
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