pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "SherpaOnnxGitHubReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8")
            patternLayout {
                artifact("[artifact]-[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.k2fsa", "sherpa-onnx")
            }
        }
    }
}

rootProject.name = "memoflow-android"
include(":app")
