# Τι χρειάζομαι από σένα

## ✅ Παραδόθηκαν
| Asset | Πού μπήκε |
|---|---|
| PDF template (.docx + .pdf) | `assets/pdf-template/` — placeholders αποκωδικοποιημένοι |
| Δείγμα παραγόμενου PDF | `assets/pdf-template/sample-output.pdf` |
| Logo & splash | `assets/branding/` |
| Brand χρώμα | `#00E2A2` — διαβάστηκε από το AppSheet theme |
| Στοιχεία επικοινωνίας | ΓΙΩΡΓΟΣ ΔΟΥΡΑΜΑΝΗΣ · 6945773605 · tovapsimo.gr |

---

## ⏳ Εκκρεμούν — με σειρά προτεραιότητας

### 1. Keystore υπογραφής (πρέπει να γίνει ΠΡΩΤΟ)
Πρέπει να προηγηθεί των Google credentials, γιατί το SHA-1 του keystore μπαίνει στο OAuth client.
Αν αλλάξει το keystore μετά, σπάει το Google Sign-In.

Χρειάζεται JDK. Αν δεν έχεις, πες μου και το φτιάχνουμε αλλιώς.
```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias prosfora
```
Μετά:
```bash
keytool -list -v -keystore release.jks -alias prosfora | grep SHA1
```
Στείλε μου το **SHA-1** και βάλε τα secrets στο GitHub (οδηγίες: [README](../README.md#signing-one-time-setup)).

### 2. Google Cloud project — για Drive backup + PDF από το template
Χωρίς αυτό δεν μπορεί το app να διαβάσει το Google Doc ούτε να γράψει backup.

1. [console.cloud.google.com](https://console.cloud.google.com) → νέο project (π.χ. `prosfores-app`)
2. **APIs & Services → Library** → ενεργοποίησε: **Google Drive API** και **Google Docs API**
3. **OAuth consent screen** → External → πρόσθεσε τον εαυτό σου ως *test user*
4. **Credentials → Create credentials → OAuth client ID → Android**
   - Package name: `gr.prosfora.app`
   - SHA-1: αυτό από το βήμα 1
5. Στείλε μου το **Client ID**

> Το app θα ζητήσει scopes `drive.file` + `documents.readonly` — δηλαδή πρόσβαση **μόνο** στα αρχεία που δημιουργεί το ίδιο, συν το συγκεκριμένο template. Όχι σε όλο το Drive σου.

### 3. Δεδομένα προς μεταφορά
Google Sheet: `1KJETbxLzQF2vWms7BIC_Vv7JOPiIE2BeUfOR-G1GhXU`
File → Download → **Microsoft Excel (.xlsx)** → βάλ' το στο `migration/source/`

### 4. Στοιχεία SMTP
Δεν τα θέλω εγώ — τα βάζεις **εσύ μέσα στο app** (Ρυθμίσεις), και μένουν κρυπτογραφημένα στο κινητό.
Πες μου μόνο ποιον πάροχο θα χρησιμοποιήσεις ώστε να επιβεβαιώσω τις σωστές ρυθμίσεις:
- **Gmail** → smtp.gmail.com:587, χρειάζεται **App Password** (Google Account → Security → 2-Step Verification → App passwords)
- **Δικό σου hosting** (π.χ. mail.tovapsimo.gr) → στείλε μου host/port/TLS από τον πάροχο

---

## Σειρά εξάρτησης
```
keystore → SHA-1 → OAuth client ID → Drive backup + PDF από template → πλήρες email με συνημμένο
                                   ↘ (ανεξάρτητα) .xlsx → migration δεδομένων
```
