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
    override fun apply(target: Project) {
        target.gradle.projectsEvaluated {
            var appVariantNames = emptyList<String>()

            target.subprojects.forEach { project ->
                if (isAppProject(project)) {
                    appVariantNames = getAppVariantNames(project)
                } else if (isAndroidLibraryProject(project)) {
                    project.tasks.getByName("preBuild").doFirst {
                        println("preBuild doFirst: ${project.name}")
                    }

                    if (appVariantNames.isEmpty()) println("No variants information collected.")

                    appVariantNames.forEach { variantName ->
                        project.tasks.getByName("assemble${variantName.capitalized()}").doLast {
                            println(getOutputFile(project, variantName)?.absolutePath)
                        }
                    }
                } else {
                    println("This project is not a app or android module. project name: ${project.name}")
                }

                getImplementationConfiguration(project)?.dependencies?.forEach { dependency ->
                    if (dependency is ProjectDependency) {
                        println(dependency.name)
                    }
                }
            }
        }
    }

    private fun isAndroidLibraryProject(project: Project) =
        project.extensions.findByType(LibraryExtension::class.java) != null

    private fun getOutputFile(project: Project, variantName: String): File? {
        return project.extensions.findByType(LibraryExtension::class.java)?.libraryVariants?.first {
            it.name.equals(
                variantName
            )
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