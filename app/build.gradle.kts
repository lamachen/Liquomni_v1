import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  // alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.lfmomni.vxzqkj"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  packaging {
    jniLibs {
      // Die App lädt genau EINE eigene Engine-Bibliothek: libllama.so aus
      // app/src/main/jniLibs/arm64-v8a (gebaut von native/build_engine.sh; llama.cpp + ggml +
      // Vulkan + mtmd + omni_shim). Es gibt keine JNI-Bridge (librnllama*.so) mehr.
      //
      // Die frühere Dependency `llamacpp-kotlin` ist entfernt, weil sie eigene
      // librnllama*.so / libllama.so mitbrachte (CPU-only, ohne mtmd). Die Excludes
      // unten sind der Gürtel zum Hosenträger: falls je wieder eine Dependency
      // librnllama*.so einschleppt, landet sie nicht im APK. pickFirsts bleibt nur für
      // libllama*.so, damit die eigene Datei bei einem Duplikat gewinnt (Projekt-Quellen
      // werden vor Abhängigkeiten gemergt).
      //
      // Dass es wirklich so ist, prüft `:app:auditNativeLibsGate` (Teil von assembleRelease).
      // Siehe docs/NATIVE-ENGINE-BACKENDS.md.
      useLegacyPackaging = true
      pickFirsts += listOf("lib/arm64-v8a/libllama.so")
      excludes += listOf("lib/arm64-v8a/librnllama*.so")
    }
  }
}

// native_audit.py liegt im Repo-Root unter native/, NICHT unter app/native/.
// (file("native/...") bezog sich auf das app-Modul und zeigte ins Leere.)
val nativeAuditScript = rootProject.file("native/native_audit.py")
val pythonExe = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"

// Prüft die gebündelten arm64-v8a-Bibliotheken: enthaltene Backends, exportierte
// Symbole, Build-Nummer, Duplikate. Braucht nur python (keine Toolchain, kein NDK).
//   ./gradlew :app:auditNativeLibs              -> Bericht (bricht nie ab)
val auditNativeLibs by tasks.registering(Exec::class) {
  group = "verification"
  description = "Auditiert app/src/main/jniLibs/arm64-v8a (Backends, Symbole, Build-Nummer)"
  val jniDir = file("src/main/jniLibs/arm64-v8a")
  inputs.dir(jniDir)
  workingDir = rootDir
  commandLine(
    pythonExe,
    nativeAuditScript.path,
    jniDir.path,
    "--json",
    layout.buildDirectory.file("native-audit-jniLibs.json").get().asFile.path
  )
}

// Gate: bricht ab, wenn die gebündelte Engine KEIN GPU-Backend enthält.
// Hängt an assembleRelease, damit nie wieder still ein CPU-only-Blob ausgeliefert wird.
val auditNativeLibsGate by tasks.registering(Exec::class) {
  group = "verification"
  description = "Bricht ab, wenn libllama.so kein GPU-Backend (Vulkan/OpenCL/Hexagon) enthält"
  val jniDir = file("src/main/jniLibs/arm64-v8a")
  inputs.dir(jniDir)
  workingDir = rootDir
  commandLine(pythonExe, nativeAuditScript.path, jniDir.path, "--gate", "gpu")
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
  dependsOn(auditNativeLibsGate)
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // Entfernt: bringt CPU-only librnllama*.so mit; die App nutzt jetzt die eigene libllama.so + omni_shim.
  // implementation(libs.llamacpp.kotlin)
  implementation("net.java.dev.jna:jna:5.14.0@aar")
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.jna)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
