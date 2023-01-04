package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project

class ModuleBuildingFaster : Plugin<Project> {
    override fun apply(target: Project) {
        target.task("cacheArtifacts") {
            doLast {
                println("hello zhaolin")
            }
        }
    }
}