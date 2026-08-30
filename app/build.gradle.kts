plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// versionCode comes from CI (GitHub Actions run number) so every build is unique.
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"

/**
 * The offer screens/template already have their storage model. This small,
 * deterministic source transform wires the real presentation layer into the
 * existing screen without relying on fragile multi-line matches.
 */
val wireOfferExtrasPresentation by tasks.registering {
    doLast {
        fun replaceOnce(path: String, from: String, to: String) {
            val source = file(path)
            val text = source.readText()
            if (text.contains(to)) return
            val index = text.indexOf(from)
            check(index >= 0) { "Δεν βρέθηκε το σημείο '$from' στο $path" }
            source.writeText(text.substring(0, index) + to + text.substring(index + from.length))
        }

        replaceOnce(
            "src/main/java/gr/prosfora/app/ui/offers/OfferDetailScreen.kt",
            "item { ExtrasCard(current, viewModel) }",
            "item { OfferExtrasCard(current, viewModel) }",
        )
        replaceOnce(
            "src/main/java/gr/prosfora/app/ui/offers/OfferDetailScreen.kt",
            "item { VatCard(current, viewModel) }",
            "item { OfferTotalsCard(current, viewModel) }",
        )

        // In DocxTemplate the regular extra rows must be cloned from the
        // non-bold row. Only the spaces total and grand total are bold.
        val template = file("src/main/java/gr/prosfora/app/doc/DocxTemplate.kt")
        var xml = template.readText()
        if (!xml.contains("val regularBaseRow = baseRow")) {
            val old = "baseRow = boldRow(baseRow)\n        var result = xml.substring(0, baseOpen) + baseRow + xml.substring(baseClose)"
            val new = "val regularBaseRow = baseRow\n        baseRow = boldRow(baseRow)\n        var result = xml.substring(0, baseOpen) + baseRow + xml.substring(baseClose)"
            val at = xml.indexOf(old)
            check(at >= 0) { "Δεν βρέθηκε το baseRow block στο DocxTemplate.kt" }
            xml = xml.substring(0, at) + new + xml.substring(at + old.length)
        }
        if (!xml.contains("var row = regularBaseRow")) {
            val old = "var row = baseRow\n                .replace(\"&lt;&lt;[Σύνολο Χώρων]&gt;&gt;\", \"&lt;&lt;[$marker]&gt;&gt;\")"
            val new = "var row = regularBaseRow\n                .replace(\"&lt;&lt;[Σύνολο Χώρων]&gt;&gt;\", \"&lt;&lt;[$marker]&gt;&gt;\")"
            val at = xml.indexOf(old)
            check(at >= 0) { "Δεν βρέθηκε το cloneRow block στο DocxTemplate.kt" }
            xml = xml.substring(0, at) + new + xml.substring(at + old.length)
        }
        template.writeText(xml)
    }
}

android {
    namespace = "gr.prosfora.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "gr.prosfora.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
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

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/NOTICE",
            "META-INF/NOTICE.md",
            "META-INF/NOTICE.txt",
            "META-INF/LICENSE",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE.txt",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.androidx.security.crypto)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.core.splashscreen)
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(wireOfferExtrasPresentation)
}
