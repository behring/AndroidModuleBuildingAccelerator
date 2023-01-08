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
    private var artifacts: List<File> = emptyList()

    override fun apply(target: Project) {
        target.gradle.addListener(TimingsListener())
        addMavenPublishPluginToSubProject(target)
        convertDependencyConfiguration(target)
    }

    private fun addMavenPublishPluginToSubProject(target: Project) {
        target.subprojects.forEach { it.plugins.apply("maven-publish") }
    }

    private fun convertDependencyConfiguration(target: Project) {
        target.gradle.projectsEvaluated {
            val rootProjectMavenLocalDir = "${System.getProperties()["user.home"]}/.m2/repository/${target.groupPath()}"
            println("Artifacts directory: $rootProjectMavenLocalDir")
            artifacts = collectExistArtifacts(rootProjectMavenLocalDir)
            lateinit var appVariantNames: List<String>

            target.subprojects.forEach { project ->
                if (isAppProject(project)) {
                    appVariantNames = getAppVariantNames(project)
                    getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
                        if (dependency is ProjectDependency) {
                            println("$project depends on ${dependency.dependencyProject}")
                            if (isExistAllAppVariantArtifactInMavenLocalRepo(dependency.dependencyProject, appVariantNames)) {
                                println("Start converting project dependency to artifact with ${dependency.dependencyProject} in $project")
                                convertProjectDependencyToArtifactsDependency(
                                    project,
                                    dependency.dependencyProject,
                                    appVariantNames
                                )
                            }
                        }
                    }

                    getImplementationConfiguration(project)?.dependencies?.removeIf {
                        (it is ProjectDependency) && isExistAllAppVariantArtifactInMavenLocalRepo(it.dependencyProject, appVariantNames)
                    }

                } else if (isAndroidLibraryProject(project)) {
                    if (appVariantNames.isEmpty()) println("No variants information collected.")

                    appVariantNames.forEach { variantName ->
                        configDependencyTaskForMavenPublishTasks(project, variantName)
                        configMavenPublishPluginForLibraryWithAppVariant(project, variantName)
                    }
                } else {
                    println("This project is not a app or android module. project name: ${project.name}")
                }
            }
        }
    }

    private fun Project.groupPath() = group.toString().replace(".","/")

    private fun configMavenPublishPluginForLibraryWithAppVariant(project: Project, variantName: String) {
        project.extensions.getByType(PublishingExtension::class.java)
            .publications.maybeCreate(project.name + variantName.capitalized(), MavenPublication::class.java).run {
                groupId = project.rootProject.group.toString()
                artifactId = "${project.name}-$variantName"
                version = project.version.toString()
                artifact(getOutputFile(project, variantName))
            }
    }

    private fun configDependencyTaskForMavenPublishTasks(project: Project, variantName: String) {
       project.tasks.whenTaskAdded {
           if (name.startsWith("publish${project.name.capitalized()}${variantName.capitalized()}PublicationTo")) {
               dependsOn("assemble${variantName.capitalized()}")
           }
       }
    }

    private fun collectExistArtifacts(rootProjectMavenLocalDir: String) =
        File(rootProjectMavenLocalDir).walkTopDown().filter { it.isFile && it.extension == "aar" }.toList()


    private fun isExistAllAppVariantArtifactInMavenLocalRepo(project: Project, appVariantNames: List<String>) =
        appVariantNames.all { variantName ->
            artifacts.any { artifact -> artifact.name.contains("${project.name}-${variantName}-${project.version}", true) }.apply {
                if (!this) println("artifact ${project.name}-${variantName}-${project.version}.aar is not exists in MavenLocal repository. can't convert to artifact dependency.")
            }
        }

    private fun convertProjectDependencyToArtifactsDependency(
        project: Project,
        dependencyProject: Project,
        appVariantNames: List<String>
    ) {
        appVariantNames.forEach { appVariantName ->
            project.dependencies.add(
                "${appVariantName}Implementation",
               "${dependencyProject.rootProject.group}:${dependencyProject.name}-$appVariantName:${dependencyProject.version}"
            )
        }
    }

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