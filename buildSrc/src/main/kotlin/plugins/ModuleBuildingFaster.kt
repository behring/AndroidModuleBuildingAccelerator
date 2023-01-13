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
import org.gradle.kotlin.dsl.create
import java.io.File
import java.util.*

class ModuleBuildingFaster : Plugin<Project> {
    companion object {
        // All project dependencies will be converted to artifacts dependencies when this value is true.
        // Please make sure you have published all artifacts for relevant projects.
        const val PLUGIN_ENABLE_SWITCH_KEY = "buildingFaster.enable"

        // This property is used to control which modules will be as a project dependency.
        // if the value is null, all android libraries will be disabled for all tasks.
        const val WORKSPACE = "buildingFaster.workspace"

        const val SEPARATOR = "-"
        val SKIP_PARENT_PROJECT_PATH = listOf(
            ":feature",
            ":infra"
        )
    }

    private lateinit var configProperties: Properties
    private var nonWorkspaceProjects: List<Project> = emptyList()
    private lateinit var moduleSettingsExtension: ModuleSettingsExtension
    private lateinit var moduleSettings: List<ModuleSetting>

    override fun apply(target: Project) {
        configProperties = loadConfigProperties(target)
        if (!isEnablePlugin()) return
        target.gradle.addListener(TimingsListener())
        nonWorkspaceProjects = getNonWorkspaceProjects(target)
        moduleSettingsExtension = createModuleSettingsExtension(target)

        target.afterEvaluate {
            moduleSettings = getModuleSettings()
            addMavenPublishPluginToSubProject(target)
        }

        convertDependencyConfiguration(target)
        configMavenPublishing(target)
    }

    private fun createModuleSettingsExtension(target: Project): ModuleSettingsExtension =
        target.extensions.create("buildingFaster", target)

    private fun getModuleSettings() = moduleSettingsExtension.moduleSettings?.toList().orEmpty()

    private fun getModuleSetting(name: String) =
        moduleSettings.firstOrNull() { it.name == name.convertToCamelNaming().decapitalize(Locale.ROOT) }

    private fun getModuleVariants(project: Project): List<String> {
        return getModuleSetting(project.name)?.buildVariants.orEmpty()
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
        if (isReplaceToArtifactsDependencyFromProjectDependency(project)
        ) {
            project.tasks.forEach { it.enabled = false }
            println("disable tasks for ${project.path}")
        }
    }

    private fun isReplaceToArtifactsDependencyFromProjectDependency(project: Project) =
        (isAndroidLibraryProject(project) && (!isExistWorkspace(project) || getModuleSetting(project.name)?.useByAar ?: false))

    private fun addMavenPublishPluginToSubProject(target: Project) {
        getProjects(target).filter { getModuleSetting(it.name) != null }
            .forEach { it.plugins.apply("maven-publish") }
    }

    private fun convertDependencyConfiguration(target: Project) {
        val rootProjectMavenLocalDir = getMavenLocalDirForRootProject(target)
        println("Artifacts directory: $rootProjectMavenLocalDir")
        getProjects(target).forEach { project ->
            project.afterEvaluate {
                convertProjectDependencyToArtifactDependenciesForProject(project)
                removeProjectDependencies(project)

                if (isAndroidLibraryProject(project)) {
                    configAndroidPublishingVariants(project)
                    tryDisableAllTasksForNonWorkspaceProject(project)
                }
            }
        }
    }

    private fun configAndroidPublishingVariants(project: Project) {
        getModuleVariants(project).forEach { libraryVariant ->
            getLibraryExtension(project).publishing.run {
                singleVariant(libraryVariant)
            }
        }
    }

    private fun getProjects(target: Project) =
        target.subprojects.filterNot { SKIP_PARENT_PROJECT_PATH.contains(it.path) }

    private fun removeProjectDependencies(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.removeIf {
            (it is ProjectDependency) && isReplaceToArtifactsDependencyFromProjectDependency(it.dependencyProject)
        }
    }

    private fun convertProjectDependencyToArtifactDependenciesForProject(project: Project) {
        getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
            if (dependency is ProjectDependency && !isExistWorkspace(dependency.dependencyProject)) {
                println("$project depends on ${dependency.dependencyProject}")
                convertProjectDependencyToArtifactDependenciesWithExistingArtifacts(
                    project,
                    dependency.dependencyProject
                )
            }
        }
    }

    private fun getMavenLocalDirForRootProject(target: Project): String {
        return "${System.getProperties()["user.home"]}/.m2/repository/${target.groupPath()}"
    }

    private fun Project.groupPath() = group.toString().replace(".", "/")

    private fun configMavenPublishPluginForModule(project: Project) {
        getModuleSetting(project.name)?.let { moduleSettings ->
            moduleSettings.buildVariants.forEach { variant ->
                val publishingExtension =
                    project.extensions.getByType(PublishingExtension::class.java)
                val publications = publishingExtension.publications
                if (publications.findByName(variant) != null) {
                    println("Publication $variant has been created for $project")
                    return
                }

                publications.create(moduleSettings.name + variant.capitalized(), MavenPublication::class.java) {
                    val component = project.components.findByName(variant)
                    if (component != null) {
                        from(project.components.findByName(variant))
                        groupId = moduleSettings.groupId
                        artifactId = moduleSettings.artifactId
                        version = moduleSettings.version
                    } else {
                        println("Can not obtain component $variant from $project, config publication failed.")
                    }
                }
            }
        }
    }


    private fun String.convertToCamelNaming() =
        split(SEPARATOR).joinToString("") { it.toLowerCase(Locale.ROOT).capitalized() }

    private fun configDependencyTaskForMavenPublishTasks(project: Project) {
        getModuleVariants(project).forEach { variant ->
            project.tasks.whenTaskAdded {
                if (name.startsWith(
                        "publish${
                            project.name.convertToCamelNaming()
                        }${variant.capitalized()}PublicationTo"
                    )
                ) {
                    dependsOn("${project.path}:assemble${variant.capitalized()}")
                }
            }
        }
    }

    // https://developer.android.com/studio/build#sourcesets
    private fun convertProjectDependencyToArtifactDependenciesWithExistingArtifacts(
        project: Project,
        dependencyProject: Project
    ) {
        getModuleSetting(dependencyProject.name)?.run {
            buildVariants.forEach { buildVariant ->
                println(
                    "[Converting based on existing artifacts] Convert project" +
                            " dependency to artifact with ${buildVariant}Implementation(${groupId}:${artifactId}:${version}) for $project"
                )
                project.dependencies.add(
                    "${buildVariant}Implementation",
                    "${groupId}:${artifactId}:${version}"
                )
            }
        }
    }

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