# Τι χρειάζομαι από σένα

## ✅ Παραδόθηκαν
| Asset | Πού μπήκε |
|---|---|
| PDF template (.docx + .pdf) | `assets/pdf-template/` — placeholders αποκωδικοποιημένοι |
| Δείγμα παραγόμενου PDF | `assets/pdf-template/sample-output.pdf` |
| Logo & splash | `assets/branding/` |
| Brand χρώμα | `#00E2A2` — διαβάστηκε από το AppSheet theme |
| Στοιχεία επικοινωνίας | ΓΙΩΡΓΟΣ ΔΟΥΡΑΜΑΝΗΣ · 6945773605 · tovapsimo.gr |
| Δεδομένα (ΔΕΔΟΜΕΝΑ.xlsx) | `migration/source/` → `assets/seed.json` |
| Keystore υπογραφής | `Documents/Prosfora-keystore/` · GitHub Secrets ρυθμισμένα |
| Google Cloud + OAuth client | project `prosfora-tovapsimo` — [λεπτομέρειες](google-cloud.md) |

---

## ⏳ Εκκρεμεί μόνο ένα

### Gmail App Password (για την αποστολή email)
Το SMTP το ρυθμίζεις **εσύ μέσα στο app** — δεν χρειάζεται να μου το στείλεις.

1. [myaccount.google.com/security](https://myaccount.google.com/security) → ενεργοποίησε **2-Step Verification** αν δεν είναι
2. Μετά → **App passwords** → δημιούργησε ένα για «Προσφορές»
3. Στο app: Ρυθμίσεις → πάτα το chip **Gmail** (γεμίζει smtp.gmail.com:587 + STARTTLS)
4. Όνομα χρήστη: το gmail σου · Κωδικός: το **app password** (16 χαρακτήρες, όχι ο κανονικός σου)
5. Διεύθυνση αποστολέα: το gmail σου
6. Πάτα **Δοκιμή σύνδεσης** — πρέπει να πει «Η σύνδεση πέτυχε»
