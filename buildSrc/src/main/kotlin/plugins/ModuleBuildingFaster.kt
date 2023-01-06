package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.get
import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.configurationcache.extensions.capitalized
import java.io.File

class ModuleBuildingFaster : Plugin<Project> {
    private lateinit var appVariantNames: List<String>
    private var artifacts: List<File> = emptyList()

    override fun apply(target: Project) {
        val cacheArtifactsRootDir = "${target.projectDir}/.gradle/artifacts"
        println("Artifacts directory: $cacheArtifactsRootDir")
        artifacts = collectExistArtifacts(cacheArtifactsRootDir)

        target.gradle.projectsEvaluated {
            val isExistDir = checkAndCreateDirs(cacheArtifactsRootDir)
            if (!isExistDir) {
                println("Dir $cacheArtifactsRootDir create failed, ModuleBuildingFaster plugin stop work.")
                return@projectsEvaluated
            }

            target.subprojects.forEach { project ->
                if (isAppProject(project)) {
                    appVariantNames = getAppVariantNames(project)
                } else if (isAndroidLibraryProject(project)) {
                    if (appVariantNames.isEmpty()) println("No variants information collected.")

                    appVariantNames.forEach { variantName ->
                        project.tasks.getByName("assemble${variantName.capitalized()}").doLast {
                            getOutputFile(project, variantName)?.let {
                                val destinationFile =
                                    File("$cacheArtifactsRootDir/${project.name}/${it.name}")
                                if (destinationFile.exists()) destinationFile.delete()

                                it.copyTo(File("$cacheArtifactsRootDir/${project.name}/${it.name}"))
                                    .run {
                                        println("Cached aar file, path: ${absolutePath}, corresponding to variant: $variantName")
                                    }
                            }
                        }
                    }
                } else {
                    println("This project is not a app or android module. project name: ${project.name}")
                }

                getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
                    if (dependency is ProjectDependency) {
                        println("$project depends on ${dependency.dependencyProject}")
                        if (isExistArtifact(dependency.dependencyProject)) {
                            println("Start converting $project dependency to artifact")
                            convertProjectDependencyToArtifactsDependency(
                                project,
                                dependency.dependencyProject
                            )
                        }
                    }
                }

                getImplementationConfiguration(project)?.dependencies?.removeIf {
                    (it is ProjectDependency) && isExistArtifact(it.dependencyProject)
                }
            }
        }
    }

    private fun collectExistArtifacts(artifactsRootDir: String) =
        File(artifactsRootDir).walkTopDown().filter { it.isFile && it.extension == "aar" }.toList()

    private fun checkAndCreateDirs(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.isDirectory) return file.mkdirs()
        return true
    }

    private fun isExistArtifact(project: Project) =
        artifacts.any { it.name.contains(project.name, true) }

    private fun convertProjectDependencyToArtifactsDependency(
        project: Project,
        dependencyProject: Project
    ) {
        appVariantNames.forEach { appVariantName ->
            artifacts.firstOrNull {
                it.name.contains("${dependencyProject.name}-${appVariantName}")
            }?.let { artifact ->
                project.dependencies.add(
                    "${appVariantName}Implementation",
                    project.files(artifact.path)
                )
            }
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