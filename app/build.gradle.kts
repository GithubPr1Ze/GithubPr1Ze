import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Clé de publication privée : chemin donné par « tachoperso.signing » dans local.properties
// (jamais versionné), pointant vers un fichier storeFile / storePassword / keyAlias / keyPassword.
val signingProps: Properties? = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.isFile) local.inputStream().use { load(it) }
}.getProperty("tachoperso.signing")?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "fr.tachoperso.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.tachoperso.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.9.0"
    }

    signingConfigs {
        // Clé de test versionnée : builds de développement et GitHub Actions uniquement.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Captures d'écran sur JVM (Robolectric + Roborazzi), avec un .DDD réel si présent dans samples/.
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
    systemProperty("tacho.sample", rootProject.file("samples/carte-reelle.DDD").absolutePath)
    systemProperty("tacho.shots", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("fr.tachoperso:core:1.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
