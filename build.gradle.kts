import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}
plugins { id("com.lagradost.cloudstream3.gradle") apply false }
allprojects { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
fun Project.cloudstream(block: CloudstreamExtension.() -> Unit) = extensions.configure("cloudstream", block)
fun Project.android(block: BaseExtension.() -> Unit) = extensions.configure("android", block)

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    cloudstream { setRepo("davdan229/kadrograd") }
    android {
        namespace = "ru.kadrograd"
        compileSdkVersion(35)
        defaultConfig { minSdk = 21; targetSdk = 35 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions", "-Xannotation-default-target=param-property")
        }
    }
    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        add("implementation", kotlin("stdlib"))
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
        add("implementation", "org.jsoup:jsoup:1.22.1")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        add("testImplementation", "junit:junit:4.13.2")
        add("testImplementation", "com.squareup.okhttp3:mockwebserver3:5.3.2")
        add("testImplementation", files(gradle.gradleUserHomeDir.resolve("caches/cloudstream/cloudstream/cloudstream.jar")))
        add("testImplementation", "io.ktor:ktor-http:3.5.0")
        add("testImplementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        add("testImplementation", "dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
    }
}
