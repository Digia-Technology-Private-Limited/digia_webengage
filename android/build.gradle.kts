plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.vanniktech.maven.publish") version "0.29.0"
    id("maven-publish")
    id("signing")
}

version = "1.0.0"

android {
    namespace = "com.digia.webengage"
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


val signingKeyId = findProperty("signingInMemoryKeyId") as String? ?: ""
val signingPassword = findProperty("signingInMemoryKeyPassword") as String? ?: ""
val keyFile = rootProject.file("private-key.asc")

signing {
    if (keyFile.exists()) {
        useInMemoryPgpKeys(signingKeyId, keyFile.readText(), signingPassword)
        sign(publishing.publications)
    }
}
