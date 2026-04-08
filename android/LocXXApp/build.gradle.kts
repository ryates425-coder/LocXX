import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.locxx.app"
    compileSdk = 35

    val nakamaProps = Properties()
    val nakamaPropFile = rootProject.file("local.properties")
    if (nakamaPropFile.exists()) {
        nakamaPropFile.inputStream().use { nakamaProps.load(it) }
    }
    val nakamaHost = nakamaProps.getProperty("nakama.host", "127.0.0.1").trim()
        .replace("\\", "\\\\").replace("\"", "\\\"")
    val nakamaPort = nakamaProps.getProperty("nakama.port", "7350").toIntOrNull() ?: 7350
    val nakamaSsl = nakamaProps.getProperty("nakama.ssl", "false").trim().lowercase() == "true"
    val nakamaKey = nakamaProps.getProperty("nakama.serverKey", "defaultkey").trim()
        .replace("\\", "\\\\").replace("\"", "\\\"")

    defaultConfig {
        applicationId = "com.locxx.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "NAKAMA_HOST", "\"$nakamaHost\"")
        buildConfigField("int", "NAKAMA_PORT", "$nakamaPort")
        buildConfigField("boolean", "NAKAMA_USE_SSL", "$nakamaSsl")
        buildConfigField("String", "NAKAMA_SERVER_KEY", "\"$nakamaKey\"")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Nakama's JitPack jar embeds com.google.api.* classes that also exist in
// proto-google-common-protos (pulled by grpc-protobuf), which fails
// checkDuplicateClasses — but excluding common-protos drops com.google.type.Date
// and crashes WebSocketClient. We strip duplicate com/google/api/* from the
// nakama jar and depend on its transitives explicitly (see heroiclabs/nakama-java build.gradle).
val nakamaJavaVersion = "v2.5.3"
val nakamaStrippedJar =
    layout.buildDirectory.file("nakama/nakama-java-$nakamaJavaVersion-stripped.jar")

val stripNakamaGoogleApi = tasks.register("stripNakamaGoogleApi") {
        val nakamaJarOnly =
            configurations.detachedConfiguration(
                dependencies.create("com.github.heroiclabs.nakama-java:nakama-java:$nakamaJavaVersion"),
            ).apply {
                isTransitive = false
                isCanBeConsumed = false
                isCanBeResolved = true
            }
        inputs.files(nakamaJarOnly)
        outputs.file(nakamaStrippedJar)

        doLast {
            val input = nakamaJarOnly.singleFile
            val output = nakamaStrippedJar.get().asFile
            output.parentFile.mkdirs()
            val skipPrefix = "com/google/api/"
            ZipOutputStream(output.outputStream()).use { zos ->
                ZipFile(input).use { zf ->
                    zf.entries().asSequence().forEach { entry ->
                        val name = entry.name
                        if (name.startsWith(skipPrefix)) return@forEach
                        val outEntry = ZipEntry(name).apply { time = entry.time }
                        zos.putNextEntry(outEntry)
                        if (!entry.isDirectory) {
                            zf.getInputStream(entry).use { inp -> inp.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

dependencies {
    implementation(project(":lan-gaming"))
    implementation(project(":locxx-rules"))
    implementation(files(nakamaStrippedJar).builtBy(stripNakamaGoogleApi))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.grpc:grpc-protobuf:1.68.0")
    implementation("io.grpc:grpc-core:1.68.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.google.protobuf:protobuf-java:4.28.2")
    implementation("com.google.protobuf:protobuf-java-util:4.28.2")
    implementation("io.grpc:grpc-okhttp:1.68.0") {
        exclude(group = "com.squareup.okio", module = "okio")
    }
    implementation("com.squareup.okio:okio:3.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("io.grpc:grpc-netty-shaded:1.68.0")
    implementation("io.grpc:grpc-stub:1.68.0")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
