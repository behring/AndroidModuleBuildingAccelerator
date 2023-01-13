package plugins

import groovy.lang.Closure
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project

open class ModuleSetting(val name: String) {
    var groupId: String = ""
    var artifactId: String = ""
    var version: String = ""
    var useByAar: Boolean = false
    var buildVariants: List<String> = emptyList()
    fun buildVariants(vararg variant: String) {
        buildVariants = variant.toList()
    }
}

open class ModuleSettingsExtension(val target: Project) {
    var mavenReleaseRepoPath: String = ""
    var mavenSnapshotRepoPath: String = ""

    var moduleSettings: NamedDomainObjectContainer<ModuleSetting>? = null

    open fun moduleSettings(configureClosure: Closure<ModuleSetting>) {
        moduleSettings = target.container(ModuleSetting::class.java).configure(configureClosure)
    }
}