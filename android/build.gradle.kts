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
    implementation(libs.webengage.sdk)
    implementation(libs.webengage.personalization)
    implementation(libs.engage)
    implementation(libs.coroutines)
    implementation(libs.compose.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    // org.json is provided by android.jar at compile time but the Android stubs throw at runtime
    // during JVM unit tests. The standalone artifact supplies the real implementation.
    testImplementation("org.json:json:20231013")
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
