import java.net.URL
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedVoskAssets = layout.buildDirectory.dir("generated/voskAssets")
val voskModelDir = generatedVoskAssets.map { it.dir("model") }
val downloadVoskModel by tasks.registering {
    outputs.dir(voskModelDir)
    doLast {
        val targetDir = voskModelDir.get().asFile
        if (targetDir.resolve("am/final.mdl").exists() && targetDir.resolve("graph").exists()) return@doLast
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        val zipFile = layout.buildDirectory.file("vosk-model-en-us-0.22-lgraph.zip").get().asFile
        zipFile.parentFile.mkdirs()
        URL("https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip").openStream().use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }
        val unpackDir = layout.buildDirectory.dir("vosk-unpacked").get().asFile
        unpackDir.deleteRecursively()
        unpackDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val prefix = "vosk-model-en-us-0.22-lgraph/"
                if (!entry.name.startsWith(prefix)) continue
                val relative = entry.name.removePrefix(prefix)
                if (relative.isBlank()) continue
                val out = unpackDir.resolve(relative)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        check(File(unpackDir, "am/final.mdl").exists()) { "Downloaded Vosk model archive is incomplete" }
        unpackDir.copyRecursively(targetDir, overwrite = true)
    }
}

android {
    namespace = "com.jarvis.prototype"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jarvis.prototype"
        minSdk = 26
        targetSdk = 32
        versionCode = 2
        versionName = "0.2.0"
    }
    sourceSets["main"].assets.srcDir(generatedVoskAssets)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild").configure { dependsOn(downloadVoskModel) }

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
