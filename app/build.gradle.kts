import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing secrets live outside the repo. Set REALM_RECORDER_KEYSTORE to a
// keystore.properties file (storeFile, storePassword, keyAlias, keyPassword).
val ksPath = System.getenv("REALM_RECORDER_KEYSTORE")
    ?: "/workspace/realm-recorder/keystore/keystore.properties"
val ksPropsFile = file(ksPath)
val ksProps = Properties()
if (ksPropsFile.exists()) {
    ksPropsFile.inputStream().use { ksProps.load(it) }
}

android {
    namespace = "com.realmvc.recorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.realmvc.recorder"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        if (ksProps.isNotEmpty()) {
            create("release") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}
