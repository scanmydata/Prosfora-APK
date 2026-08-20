# Προσφορές — Native Android app

Native αντικατάσταση του AppSheet app "ΠΡΟΣΦΟΡΕΣ" (Kotlin + Jetpack Compose).
Πλήρες πλάνο: [appsheet-native-migration-plan.md](appsheet-native-migration-plan.md).

## Κατάσταση φάσεων
| Φάση | Τι είναι | Status |
|---|---|---|
| 0 | Καταγραφή AppSheet schema | ⏳ Εκκρεμεί — χρειάζεται πρόσβαση στον editor ([template](docs/phase0-appsheet-schema.md)) |
| 1 | Repo + Android scaffold + CI/CD auto-release | ✅ Έτοιμο |
| 2 | Firebase (Firestore + Auth) | ⬜ |
| 3 | Migration script Sheet → Firestore | ⬜ |
| 4 | Views + CRUD | ⬜ (τώρα υπάρχει placeholder οθόνη με sample data) |
| 5 | Email automation (Cloud Function) | ⬜ |
| 6 | Google Drive integration | ⬜ |
| 7 | Testing + πρώτο release | ⬜ |

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
