package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.get

class ModuleBuildingFaster : Plugin<Project> {
    override fun apply(target: Project) {
        target.task("cacheArtifacts") {
            doLast {
                println("hello zhaolin")
                val implementation = target.configurations.getByName("implementation")
            }
        }
    }
}