import groovy.json.JsonSlurper


class VersionInfo {
  val appId: String
  val version: String
  val versionCode: Int
  constructor(givenId: String, givenVersion: String, givenCode: Int) {
    appId = givenId
    version = givenVersion
    versionCode = givenCode
  }
}

fun getVersionInfo(project: Project): VersionInfo {
  val json = JsonSlurper()
  val packageJsonPath = project.file("../../package.json")

  val packageJson = json.parse(packageJsonPath) as Map<String, Any>
  val versionName = packageJson["version"] as String
  val appName = "io.freetubeapp." + packageJson["name"]
  val parts = versionName.split("-")
  val numbers = parts[0].split(".")
  val major = numbers.getOrNull(0)?.toIntOrNull() ?: 0
  val minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0
  val patch = numbers.getOrNull(2)?.toIntOrNull() ?: 0
  // "0.25.2-nightly-123" carries the build after the second dash, "0.25.2.123" as a
  // fourth number. anything else, "1.0.0-beta" say, has no build number
  val build = if (parts.size > 2) {
    parts[2].toIntOrNull() ?: 0
  } else {
    numbers.getOrNull(3)?.toIntOrNull() ?: 0
  }

  // Each part has a fixed width in the version code, so one that outgrows its width would
  // silently produce the code of a different version. Fail the build instead.
  require(major in 0..20 && minor in 0..99 && patch in 0..99 && build in 0..9999) {
    "version $versionName does not fit the version code scheme " +
      "(major <= 20, minor <= 99, patch <= 99, build <= 9999)"
  }

  val versionCode = major * 100000000 + minor * 1000000 + patch * 10000 + build

  return VersionInfo(appName, versionName, versionCode)
}

val versionInfo = getVersionInfo(project)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    signingConfigs {
      getByName("debug") {
        // inject signing config
      };
    }
    namespace = "io.freetubeapp.freetube"
    compileSdk = 34
    dataBinding {
        enable = true
    }
    defaultConfig {
        applicationId = versionInfo.appId
        minSdk = 29
        targetSdk = 34
        versionCode = versionInfo.versionCode
        versionName = versionInfo.version

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // in this case debug is just a name of a signing config
            // the release workflow injects the release keystore info into the "debug" signing config
            // i tried to add a signing config called "release", but i got build errors :(
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

}
