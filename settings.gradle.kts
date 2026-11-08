pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ADI AI"
include(":app")
// Optional: drop the official llama.cpp `examples/llama.android/llama` module into ./llama to enable real on-device GGUF inference (see README).
if (file("llama").exists()) include(":llama")
