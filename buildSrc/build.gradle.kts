plugins {
    `kotlin-dsl`
}

buildscript {
    repositories {
        google()
        mavenCentral()

    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.3.1")
    }
}

gradlePlugin {
    plugins {
        create("ModuleBuildingFaster") {
            id = "ModuleBuildingFaster"
            implementationClass = "plugins.ModuleBuildingFaster"
        }
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:7.3.1")
}