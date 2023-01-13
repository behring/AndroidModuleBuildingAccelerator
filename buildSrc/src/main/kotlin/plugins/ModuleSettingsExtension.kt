package plugins

import groovy.lang.Closure
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project

open class ModuleSetting(val name: String) {
    var groupId: String = ""
    var artifactId: String = ""
    var version: String = ""
    var useByAar: Boolean = false
}

open class ModuleSettingsExtension(val target: Project) {
    lateinit var moduleSettings: NamedDomainObjectContainer<ModuleSetting>

    open fun moduleSettings(configureClosure: Closure<ModuleSetting>) {
        moduleSettings = target.container(ModuleSetting::class.java).configure(configureClosure)
    }
}