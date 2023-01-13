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
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.20")
    }
}

gradlePlugin {
    plugins {
        create("ModuleBuildingAccelerator") {
            id = "ModuleBuildingAccelerator"
            implementationClass = "plugins.ModuleBuildingAccelerator"
        }
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:7.3.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.20")
}