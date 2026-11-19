plugins {
    id("com.android.application")
}

// Automatically extract version name and code from pc/m328v6_web_host.html
fun extractVersionFromHtml(): Pair<String, Int> {
    val defaultVersion = "1.0.0"
    val defaultCode = 10000
    val candidates = listOf(
        rootProject.file("../pc/m328v6_web_host.html"),
        layout.projectDirectory.file("src/main/assets/m328v6_web_host.html").asFile
    )
    val htmlFile = candidates.firstOrNull { it.exists() } ?: return defaultVersion to defaultCode

    val text = htmlFile.readText()
    val badgeRegex = Regex("""id=["']host-ver-badge["'][^>]*>\s*v?(\d+(?:\.\d+)+)\s*<""", RegexOption.IGNORE_CASE)
    val titleRegex = Regex("""<title>[^<]*?v(\d+(?:\.\d+)+)""", RegexOption.IGNORE_CASE)

    val verString = badgeRegex.find(text)?.groupValues?.get(1)
        ?: titleRegex.find(text)?.groupValues?.get(1)
        ?: defaultVersion

    val parts = verString.split('.').mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 1 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    val code = major * 10000 + minor * 100 + patch

    return verString to code
}

val (appVersionName, appVersionCode) = extractVersionFromHtml()

android {
    namespace = "com.m328v6.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.m328v6.host"
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Automatically sync m328v6_web_host.html from pc/ into app/src/main/assets before each build
val syncWebHostHtml by tasks.registering(Copy::class) {
    val sourceHtml = rootProject.file("../pc/m328v6_web_host.html")
    onlyIf { sourceHtml.exists() }
    from(sourceHtml)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(syncWebHostHtml)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
