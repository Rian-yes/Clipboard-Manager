pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
    maven { url = uri("https://maven.rikka.app/repository/maven-public/") }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Clipboard Manager"

include(":app")