buildscript {
    repositories {
        google()
    }
    dependencies {
        classpath(libs.navigation.safe.args.gradle.plugin)

    }
}// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("ModuleBuildingAccelerator")
}
apply(from = "${rootDir}/modules-publishing-config.gradle")