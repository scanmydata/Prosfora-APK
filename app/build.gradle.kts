plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"

val wireOfferExtrasPresentation by tasks.registering {
    doLast {
        val offerScreen = file("src/main/java/gr/prosfora/app/ui/offers/OfferDetailScreen.kt")
        var offerText = offerScreen.readText()
        if (!offerText.contains("item { OfferExtrasCard(current, viewModel) }")) {
            offerText = offerText.replace(
                "item { ExtrasCard(current, viewModel) }",
                "item { OfferExtrasCard(current, viewModel) }",
            )
        }
        if (!offerText.contains("item { OfferTotalsCard(current, viewModel) }")) {
            offerText = offerText.replace(
                "item { VatCard(current, viewModel) }",
                "item { OfferTotalsCard(current, viewModel) }",
            )
        }
        offerScreen.writeText(offerText)

        val template = file("src/main/java/gr/prosfora/app/doc/DocxTemplate.kt")
        var xml = template.readText()

        if (!xml.contains("val regularBaseRow = baseRow")) {
            val old = "baseRow = boldRow(baseRow)\n        var result = xml.substring(0, baseOpen) + baseRow + xml.substring(baseClose)"
            val replacement = "val regularBaseRow = baseRow\n        baseRow = boldRow(baseRow)\n        var result = xml.substring(0, baseOpen) + baseRow + xml.substring(baseClose)"
            val at = xml.indexOf(old)
            if (at >= 0) xml = xml.substring(0, at) + replacement + xml.substring(at + old.length)
        }

        if (!xml.contains("var row = regularBaseRow")) {
            val old = "var row = baseRow\n                .replace(\"&lt;&lt;[Σύνολο Χώρων]&gt;&gt;\", \"&lt;&lt;[${'$'}marker]&gt;&gt;\")"
            val replacement = "var row = regularBaseRow\n                .replace(\"&lt;&lt;[Σύνολο Χώρων]&gt;&gt;\", \"&lt;&lt;[${'$'}marker]&gt;&gt;\")"
            val at = xml.indexOf(old)
            if (at >= 0) xml = xml.substring(0, at) + replacement + xml.substring(at + old.length)
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
