plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

group = "com.getfounderhq"
version = "0.8.0"

android {
    namespace = "com.founderhq.journeys.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

}

dependencies {
    api(project(":journeys"))
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
}

mavenPublishing {
    coordinates(group.toString(), "journeys-compose", version.toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("FounderHQ Journeys Compose")
        description.set("FounderHQ Journeys Compose SDK for Android")
        url.set("https://github.com/FounderHQ/founderhq-journeys-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/FounderHQ/founderhq-journeys-android/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("FounderHQ")
                name.set("FounderHQ")
                email.set("tech@getfounderhq.com")
            }
        }
        scm {
            url.set("https://github.com/FounderHQ/founderhq-journeys-android")
            connection.set("scm:git:https://github.com/FounderHQ/founderhq-journeys-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/FounderHQ/founderhq-journeys-android.git")
            tag.set("v${project.version}")
        }
    }
}
