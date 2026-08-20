# Προσφορές — Native Android app

Native αντικατάσταση του AppSheet app "ΠΡΟΣΦΟΡΕΣ" (Kotlin + Jetpack Compose).
Πλήρες πλάνο: [appsheet-native-migration-plan.md](appsheet-native-migration-plan.md).

## Κατάσταση φάσεων
| Φάση | Τι είναι | Status |
|---|---|---|
| 0 | Καταγραφή AppSheet schema | ✅ [πλήρες schema](docs/phase0-appsheet-schema.md) |
| 1 | Repo + CI/CD auto-release | ✅ κάθε push στο main → νέο GitHub Release |
| 2 | Data layer (Room, offline-first) | ✅ |
| 3 | Migration δεδομένων από το Sheet | ⏳ περιμένει το `.xlsx` export |
| 4 | Οθόνες + CRUD + νέα UX σημειώσεων | ✅ |
| 5 | Email μέσω SMTP | ✅ (χωρίς συνημμένο ακόμη) |
| 6 | Drive backup + PDF από το Google Doc template | ⏳ περιμένει Google OAuth client |
| 7 | Testing + πρώτο σταθερό release | ⏳ περιμένει keystore |

Αρχιτεκτονική: [docs/architecture.md](docs/architecture.md) — **χωρίς Firebase**, χωρίς backend.
Τι χρειάζομαι από σένα: [docs/assets-needed.md](docs/assets-needed.md)

## Build
Δεν χρειάζεται Android Studio τοπικά — **το build γίνεται στο GitHub Actions**.
Κάθε push στο `main` παράγει αυτόματα νέο GitHub Release με `.apk` + `.aab`
(tag `v0.1.<run_number>`). Push ενός tag `vX.Y.Z` παράγει release με αυτό το version.

Τοπικά (αν εγκαταστήσεις JDK 17 + Android SDK):
```bash
gradle assembleRelease -PappVersionCode=1 -PappVersionName=0.1.0
```

## Signing (one-time setup)
Μέχρι να μπουν τα secrets, τα release builds υπογράφονται με το **debug key**
(εγκαθίστανται κανονικά στο κινητό, αλλά δεν γίνονται upgrade από/προς production build).

1. Δημιούργησε keystore (χρειάζεται JDK):
   ```bash
   keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias prosfora
   ```
2. Κάνε το base64:
   ```bash
   base64 -w0 release.jks > release.jks.b64
   ```
3. Πρόσθεσε τα GitHub Secrets (Settings → Secrets and variables → Actions):
   | Secret | Τιμή |
   |---|---|
   | `KEYSTORE_BASE64` | περιεχόμενο του `release.jks.b64` |
   | `KEYSTORE_PASSWORD` | το store password |
   | `KEY_ALIAS` | `prosfora` |
   | `KEY_PASSWORD` | το key password |

   ή με CLI:
   ```bash
   gh secret set KEYSTORE_BASE64 < release.jks.b64
   ```

⚠️ Το `release.jks` **δεν** μπαίνει ποτέ στο repo (είναι στο `.gitignore`). Κράτα backup — αν χαθεί, δεν μπορείς να κάνεις update το ίδιο app.
