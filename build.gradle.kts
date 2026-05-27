// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") apply false version "8.11.0"
    id("com.android.library") apply false version "8.11.0"
    id("org.jetbrains.kotlin.android") apply false version "1.9.22"
    id("com.google.devtools.ksp") apply false version "1.9.22-1.0.18" 
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}