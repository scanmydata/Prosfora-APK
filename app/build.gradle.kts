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
 * Injects the optional user-defined extra cost into the existing offer editor
 * immediately before Kotlin compilation. The database fields already exist,
 * so this patch only adds the missing UI without changing Room schema.
 */
val patchOfferExtrasUi by tasks.registering {
    doLast {
        val source = file("src/main/java/gr/prosfora/app/ui/offers/OfferDetailScreen.kt")
        val text = source.readText()
        val marker = "label = \"Νέο πρόσθετο κόστος\""
        if (text.contains(marker)) return@doLast

        val oldBlock = """
            if (!offer.scaffolding && !offer.permit) {
                Text(
                    \"Όσα δεν είναι επιλεγμένα δεν εμφανίζονται καθόλου στο PDF.\",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
""".trimIndent()

        val newBlock = """
            CustomExtraCost(
                offer = offer,
                onChange = { name, amount ->
                    viewModel.updateOffer(
                        offer.copy(
                            customExtraName = name,
                            customExtraCost = amount,
                        ),
                    )
                },
            )

            if (!offer.scaffolding && !offer.permit && offer.customExtraName.isBlank()) {
                Text(
                    \"Τα 2 προεπιλεγμένα και το νέο πρόσθετο εμφανίζονται στο PDF μόνο όταν έχουν ποσό.\",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
""".trimIndent()

        check(text.contains(oldBlock)) {
            "Δεν βρέθηκε το αναμενόμενο ExtrasCard σημείο για το custom extra patch."
        }

        var patched = text.replace(oldBlock, newBlock, limit = 1)

        val extraCostEnd = """    }
}

// ------------------------------------------------------------------- ΦΠΑ -----"""
        val customComposable = """
    }
}

@Composable
private fun CustomExtraCost(
    offer: gr.prosfora.app.data.db.OfferEntity,
    onChange: (String, Double) -> Unit,
) {
    var name by remember(offer.id) { mutableStateOf(offer.customExtraName) }
    var amountText by remember(offer.id) {
        mutableStateOf(if (offer.customExtraCost > 0.0) offer.customExtraCost.toString().removeSuffix(\".0\") else \"\")
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            \"Νέο πρόσθετο κόστος\",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        StableTextField(
            value = name,
            onValueChange = {
                name = it
                onChange(it.trim(), amountText.parseDecimal() ?: 0.0)
            },
            label = \"Ονομασία πρόσθετου\",
            debounceMillis = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StableTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    onChange(name.trim(), it.parseDecimal() ?: 0.0)
                },
                label = \"Ποσό (€)\",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                debounceMillis = 0,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = name.isNotBlank() || amountText.isNotBlank(),
                onClick = {
                    name = \"\"
                    amountText = \"\"
                    onChange(\"\", 0.0)
                },
            ) {
                Icon(Icons.Default.Clear, contentDescription = \"Καθαρισμός πρόσθετου κόστους\")
            }
        }
    }
""".trimIndent()

        check(patched.contains(extraCostEnd)) {
            "Δεν βρέθηκε το τέλος της ExtrasCard για την εισαγωγή του custom composable."
        }
        patched = patched.replace(extraCostEnd, customComposable + "\n\n// ------------------------------------------------------------------- ΦΠΑ -----", limit = 1)
        source.writeText(patched)
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
            // Populated by CI from GitHub Secrets. Locally these are absent and we
            // fall back to the debug key (see buildTypes below).
            val storePath = System.getenv("KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                // Το keystore είναι PKCS#12 (φτιάχτηκε από migration/make_keystore.py)
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

// The source patch must run before Kotlin/KSP tasks and is idempotent.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(patchOfferExtrasUi)
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
