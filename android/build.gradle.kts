plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("maven-publish")
}

version = "0.1.0"

android {
    namespace = "com.digia.engage.webengage"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("com.webengage:android-sdk:4.+")
    implementation("com.webengage:we-personalization:1.+")
    implementation(libs.engage)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("androidx.compose.runtime:runtime:1.7.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["release"])
                groupId = "com.digia"
                artifactId = "webengage"
                version = version
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
