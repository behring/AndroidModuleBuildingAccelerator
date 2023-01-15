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
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.exclude
import java.io.File
import java.net.URI
import java.util.*

class ModuleBuildingAccelerator : Plugin<Project> {
    companion object {
        // All project dependencies will be converted to artifacts dependencies when this value is true.
        // Please make sure you have published all artifacts for relevant projects.
        const val PLUGIN_ENABLE_SWITCH_KEY = "buildingAccelerator.enable"

        // This property is used to control which modules will be as a project dependency.
        // if the value is null, all android libraries will be disabled for all tasks.
        const val WORKSPACE = "buildingAccelerator.workspace"

        const val BUILDING_ACCELERATOR_EXTENSION = "buildingAccelerator"

        const val MAVEN_PUBLICATION_NAME = "allVariants"
        const val SEPARATOR = "-"
        val SKIP_PARENT_PROJECT_PATH = listOf(
            ":feature",
            ":infra",
            ":ui"
        )
    }

    private lateinit var configProperties: Properties
    private lateinit var workspaceProjects: List<Project>
    private lateinit var nonWorkspaceProjects: List<Project>
    private lateinit var moduleSettingsExtension: ModuleSettingsExtension
    private lateinit var moduleSettings: List<ModuleSetting>

    override fun apply(target: Project) {
        configProperties = loadConfigProperties(target)
        if (!isEnablePlugin()) return
        target.gradle.addListener(TimingsListener())
        val (workspaceProjects, nonWorkspaceProjects) = splitWorkspaceAndNonWorkspaceProjects(target)
        this.workspaceProjects = workspaceProjects
        this.nonWorkspaceProjects = nonWorkspaceProjects
        moduleSettingsExtension = createModuleSettingsExtension(target)

        target.afterEvaluate {
            moduleSettings = getModuleSettings()
            initMavenPublishingActions(target)
        }
        convertDependencyConfiguration(target)
        configMavenPublishing(target)
    }


    private fun convertDependencyConfiguration(target: Project) {
        getProjects(target).forEach { project ->
            project.afterEvaluate {
                configAndroidPublishingVariants(project)
                // this will not disable the relevant publishing tasks due to these tasks will be added later.
                tryDisableAllTasksForNonWorkspaceProject(project)
            }

            project.gradle.projectsEvaluated {
                convertProjectDependencyToArtifactDependenciesForProject(project)
                removeProjectDependencies(project)
            }
        }
    }

    private fun convertProjectDependencyToArtifactDependenciesForProject(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
            if (dependency is ProjectDependency && isReplaceToArtifactsDependencyFromProjectDependency(dependency.dependencyProject)) {
                println("$project depends on ${dependency.dependencyProject}")
                convertProjectDependencyToArtifactDependencies(
                    project,
                    dependency.dependencyProject
                )
            }
        }
    }

    // https://developer.android.com/studio/build#sourcesets
    private fun convertProjectDependencyToArtifactDependencies(
        project: Project,
        dependencyProject: Project
    ) {
        getModuleSetting(dependencyProject.name)?.run {
            val variants: List<String> = if (isAppProject(project)) {
                getAppVariants(project)
            } else if (isAndroidLibraryProject(project)) {
                getModuleVariants(project)
            } else {
                println("Unknown project type. project: $project")
                return
            }
            variants.forEach { variant ->
                println("Converting project dependency to artifact with ${variant}Implementation(\"${groupId}:${artifactId}:${version}\") for $project")

                val implementation = project.configurations.maybeCreate("${variant}Implementation")
                project.dependencies.add(
                    implementation.name,
                    "${groupId}:${artifactId}:${version}"
                ) {
                    workspaceProjects.mapNotNull {
                        getModuleSetting(it.name)
                    }.forEach {
                        implementation.exclude(it.groupId, it.artifactId)
                    }
                }
            }
        }
    }

    private fun configAndroidPublishingVariants(project: Project) {
        if (isAndroidLibraryProject(project)) {
            getLibraryExtension(project).publishing.run {
                multipleVariants {
                    allVariants()
                }
            }
        }
    }


    private fun configMavenPublishing(target: Project) {
        target.gradle.projectsEvaluated {
            getProjects(target).forEach { project ->
                if (isAndroidLibraryProject(project)) {
                    configDependencyTaskForMavenPublishTasks(project)
                    configMavenPublishPluginForModule(project)
                }
            }
        }
    }

    private fun configDependencyTaskForMavenPublishTasks(project: Project) {
        project.tasks.whenTaskAdded {
            if (name.startsWith("publish${MAVEN_PUBLICATION_NAME}PublicationTo")) {
                dependsOn("${project.path}:assemble")
            }
        }
    }

    private fun configMavenPublishPluginForModule(project: Project) {
        getModuleSetting(project.name)?.let { moduleSetting ->
            val publishingExtension =
                project.extensions.getByType(PublishingExtension::class.java)

            publishingExtension.repositories {
                mavenLocal()
                maven {
                    val releasesRepoUrl = URI(moduleSettingsExtension.mavenReleaseRepoPath)
                    val snapshotsRepoUrl = URI(moduleSettingsExtension.mavenSnapshotRepoPath)
                    url = if (moduleSetting.version.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
                }
            }

            val publications = publishingExtension.publications
            publications.create(MAVEN_PUBLICATION_NAME, MavenPublication::class.java) {
                val component = project.components.findByName("default")
                if (component != null) {
                    from(project.components.findByName("default"))
                    groupId = moduleSetting.groupId
                    artifactId = moduleSetting.artifactId
                    version = moduleSetting.version
                } else {
                    println("Can not obtain component \"default\" from $project, config publication failed.")
                }
            }
        }
    }

    private fun createOneKeyPublishTask(project: Project) {
        project.task("oneKeyPublish") {
            group = "publishing"
            dependsOn("publishToMavenLocal", "publish")
        }
    }

    private fun createModuleSettingsExtension(target: Project): ModuleSettingsExtension =
        target.extensions.create(BUILDING_ACCELERATOR_EXTENSION, target)

    private fun getModuleSettings() = moduleSettingsExtension.moduleSettings?.toList().orEmpty()

    private fun getModuleSetting(name: String) =
        moduleSettings.firstOrNull() { it.name == name.convertToCamelNaming().decapitalize(Locale.ROOT) }

    private fun getModuleVariants(project: Project): List<String> {
        // the libraryVariants needs to be obtained into gradle.projectsEvaluated hook.
        return project.extensions.findByType(LibraryExtension::class.java)!!.libraryVariants.map { variant ->
            variant.name
        }
    }

    private fun getAppVariants(project: Project): List<String> {
        // the libraryVariants needs to be obtained into gradle.projectsEvaluated hook.
        return project.extensions.findByType(AppExtension::class.java)!!.applicationVariants.map { variant ->
            variant.name
        }
    }

    private fun loadConfigProperties(target: Project) =
        Properties().apply {
            load(File(target.rootDir.absolutePath + "/local.properties").inputStream())
        }

    private fun splitWorkspaceAndNonWorkspaceProjects(target: Project): Pair<List<Project>, List<Project>> {
        val workspaceProjects = mutableListOf<Project>()
        val nonWorkspaceProjects = mutableListOf<Project>()

        getProjects(target).forEach {
            if( getWorkspaceModulePaths().contains(it.path)) {
                workspaceProjects.add(it)
            } else {
                nonWorkspaceProjects.add(it)
            }
        }
        return Pair(workspaceProjects, nonWorkspaceProjects)
    }

    private fun getWorkspaceModulePaths() =
        configProperties.getProperty(WORKSPACE)?.split(",")
            ?: emptyList()

    private fun isEnablePlugin() =
        (configProperties.getProperty(PLUGIN_ENABLE_SWITCH_KEY) ?: "false").toBoolean()

    private fun tryDisableAllTasksForNonWorkspaceProject(project: Project) {
        if (isReplaceToArtifactsDependencyFromProjectDependency(project)) {
            project.tasks.forEach { it.enabled = false }
            println("disable tasks for ${project.path}")
        }
    }

    private fun isReplaceToArtifactsDependencyFromProjectDependency(project: Project): Boolean {
       return (isAndroidLibraryProject(project) && !isExistWorkspace(project) && getModuleSetting(project.name)?.useByAar ?: false)
    }

    private fun initMavenPublishingActions(target: Project) {
        getProjects(target).filter { getModuleSetting(it.name) != null }
            .forEach {
                addMavenPublishPlugin(it)
                createOneKeyPublishTask(it)
            }
    }

    private fun addMavenPublishPlugin(it: Project) {
        it.plugins.apply("maven-publish")
    }

    private fun getProjects(target: Project) =
        target.subprojects.filterNot { SKIP_PARENT_PROJECT_PATH.contains(it.path) }

    private fun removeProjectDependencies(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.removeIf {
            (it is ProjectDependency) && isReplaceToArtifactsDependencyFromProjectDependency(it.dependencyProject)
        }
    }

    private fun String.convertToCamelNaming() =
        split(SEPARATOR).joinToString("") { it.toLowerCase(Locale.ROOT).capitalized() }

    private fun isExistWorkspace(project: Project) = !nonWorkspaceProjects.contains(project)

    private fun isAndroidLibraryProject(project: Project) =
        project.extensions.findByType(LibraryExtension::class.java) != null

    private fun isAppProject(project: Project) =
        project.extensions.findByType(AppExtension::class.java) != null

    private fun getLibraryExtension(project: Project): LibraryExtension {
        return project.extensions.findByType(LibraryExtension::class.java)!!
    }

    private fun getImplementationConfiguration(project: Project): Configuration? {
        return try {
            project.configurations["implementation"]
        } catch (ignore: Exception) {
            null
        }
    }
}