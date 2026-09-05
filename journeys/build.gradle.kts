plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "com.getfounderhq"
version = "0.1.0"

android {
    namespace = "com.founderhq.journeys"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.2")
    implementation("androidx.webkit:webkit:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}

mavenPublishing {
    coordinates(group.toString(), "journeys", version.toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("FounderHQ Journeys")
        description.set("FounderHQ Journeys SDK for Android")
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
