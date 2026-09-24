import groovy.json.JsonSlurper
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

val sharedVersion = providers.exec {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("go", "run", "./internal/buildinfo/cmd/version", "-format=json")
}.standardOutput.asText.map { output ->
    @Suppress("UNCHECKED_CAST")
    (JsonSlurper().parseText(output) as Map<String, Any>)
}.get()

android {
    namespace = "com.qmix.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qmix.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = (sharedVersion.getValue("androidVersionCode") as Number).toInt()
        versionName = sharedVersion.getValue("version") as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LOG_LEVEL", "\"WARN\"")
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.reports.junitXml.required.set(true)
            it.reports.html.required.set(true)
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        // Toolchain and dependencies are deliberately pinned and upgraded as
        // a reviewed unit instead of changing whenever lint sees a release.
        disable += setOf(
            "OldTargetApi",
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

jacoco {
    toolVersion = "0.8.13"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register("printSharedVersion") {
    doLast {
        println("versionName=${android.defaultConfig.versionName}")
        println("versionCode=${android.defaultConfig.versionCode}")
    }
}

val debugClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("com/qmix/tv/**/*.class")
    exclude(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
    )
}
val debugCoverageData = files(
    layout.buildDirectory.file(
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
    ),
    fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected")) {
        include("**/*.ec")
    },
)

tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(debugClasses)
    executionData.setFrom(debugCoverageData)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("jacocoDebugCoverageVerification") {
    dependsOn("jacocoDebugReport")
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(debugClasses)
    executionData.setFrom(debugCoverageData)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}
