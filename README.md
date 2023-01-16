# AndroidModuleBuildingAccelerator

It is a custom gradle plugin that is a cache mechanism that aims to save time by reusing aar files
produced by other builds. the gradle plugin by storing(locally or remotely) aar files and allowing
builds to fetch these aar files from maven repositories when a attribute from the plugin that
controls whether use aar file to build is turned on.

## Prerequisite

- AGP(Android Gradle Plugin) version more than 7.1

## Setup

1. Config maven repositories in `settings.gradle.kts` of the root project.(this can also be
   configured in other ways)
   ```kotlin
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            google()
            mavenCentral()
            // this is config
            mavenLocal()
            maven { url = uri("https://xxx.xxx.xxx/releases") }
            maven { url = uri("https://xxx.xxx.xxx/snapshots") }
        }
    }
   ```

2. add the plugin to the `build.gradle.kts`, As shown in the following code:
    ```kotlin
    buildscript {
    }
    plugins {
      id("ModuleBuildingAccelerator")
    }
   ```
3. Enable the plugin by setting `buildingAccelerator.enable` attribute in `local.properties`, the
   code is as follows:
   ```properties
      buildingAccelerator.enable=true
   ```
4. Setting current workspace in `local.properties`:
   ```properties
      buildingAccelerator.workspace=:feature:home
   ```
   > tips: When there are more than one workspace, separate the project path with a comma.

5. create a file with the suffix `.gradle` to config these publishing information of maven
   repository for each modules. Here take the `modules-publishing-config.gradle` as an example, the
   reference code is as follows:
   ```groovy
    buildingAccelerator {
        mavenReleaseRepoPath = "https://xxx.xxx.xxx/repos/releases"
        mavenSnapshotRepoPath = "https://xxx.xxx.xxx/repos/snapshots"
    
        moduleSettings {
            home {
                groupId = "cn.behring.home"
                version = "1.3.2"
                artifactId = "home"
                useByAar = true
            }
    
            payments {
                groupId = "cn.behring.payment"
                version = "1.3.0"
                artifactId = "payment"
                useByAar = true
            }
    
            network {
                groupId = "cn.behring.network"
                version = "1.0.0"
                artifactId = "network"
                useByAar = true
            }
    
            analytics {
                groupId = "cn.behring.analytics"
                version = "1.0.0"
                artifactId = "analytics"
                useByAar = true
            }
        }
    }
   ```
6. Reference the `modules-publishing-config.gradle` in the `build.gradle.kts` of root project.
   ```kotlin
    apply(from = "${rootDir}/modules-publishing-config.gradle")
   ```

## Publishing AAR artifacts

You can publish the modules that be configured in e `modules-publishing-config.gradle` to local or
remote maven repository after completing all steps above. The plugin will generated some tasks used
to publish aar files. Please make sure published the corresponding aar files, otherwise will not
fetch dependencies.

## Notes

- You must enable the plugin by setting `buildingAccelerator.enable=true` in `local.properties`,
  otherwise the plugin will not work.
- You must config modules that you want to publish in `modules-publishing-config.gradle`, otherwise
  the corresponding publishing tasks of gradle will not be generated.
- The corresponding aar files of modules will be used to build when set `useByAar = true` for
  certain module in the corresponding node of `modules-publishing-config.gradle`.(This is provided
  that the module is not to be config in `buildingAccelerator.workspace`)
- These modules will always be built with source code when config the module path
  to  `buildingAccelerator.workspace` attribute in `local.properties`.
- Don't forget publish those modules that you hope to be built with aar files.
- Almost all tasks are disable for modules that not exist in `buildingAccelerator.workspace` or
  modules that `useByAar = true`(Except for publishing-related tasks)

## About Multiple Variant publish

We can publish multiple aar files at once based on
the [`LibraryPublishing`](https://developer.android.com/reference/tools/gradle-api/7.4/com/android/build/api/dsl/LibraryPublishing)
feature of AGP. At present, The plugin only support to publish all variants.
