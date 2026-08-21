# Google Cloud — ρύθμιση (ολοκληρώθηκε 21/8/2026)

| | |
|---|---|
| Project | `prosfora-tovapsimo` |
| APIs ενεργά | Google Drive API, Google Docs API |
| OAuth client (Android) | `734218781233-sap7ivhf555e0rs2968evntfenkcf6up.apps.googleusercontent.com` |
| Package name | `gr.prosfora.app` |
| SHA-1 | `48:84:75:EE:2D:B3:3B:CB:62:73:84:7A:B5:69:A8:9B:64:3A:8B:4C` |
| Publishing status | Testing · External |
| Test user | adonis.douramanis@gmail.com |

Το Client ID **δεν** μπαίνει στον κώδικα: σε Android OAuth client η ταυτοποίηση γίνεται από
το ζεύγος *package name + SHA-1 της υπογραφής*. Γι' αυτό το keystore πρέπει να μείνει σταθερό.

## Keystore
Παράγεται από το [`migration/make_keystore.py`](../migration/make_keystore.py) (PKCS#12, χωρίς JDK).
Το αρχείο και ο κωδικός ζουν **εκτός repo**: `Documents/Prosfora-keystore/`.
Τα GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) είναι ήδη ρυθμισμένα.

> ⚠️ Αν χαθεί το keystore: δεν σπάει μόνο το update του app — σπάει και το Google Sign-In,
> γιατί αλλάζει το SHA-1. Κράτα αντίγραφο ασφαλείας του φακέλου.

## Scopes — όλα σε ένα consent window

| Scope | Τι επιτρέπει | Κατηγορία |
|---|---|---|
| `drive.file` | μόνο τα αρχεία που φτιάχνει το app: το πρότυπο και τα PDF | non-sensitive |
| `spreadsheets` | το κοινόχρηστο Sheet που παίζει τον ρόλο της βάσης | sensitive |
| `gmail.send` | **μόνο αποστολή** email — καμία ανάγνωση αλληλογραφίας | sensitive |

**Restricted scopes: κανένα.** Αυτό είναι σημαντικό — τα restricted απαιτούν
έλεγχο ασφαλείας από τρίτο φορέα (με κόστος). Τα sensitive όχι.

APIs ενεργά: Drive, Docs, **Sheets**, **Gmail**.

## Testing vs Production — απόφαση που εκκρεμεί

Τώρα το project είναι σε **Testing**. Με sensitive scopes αυτό σημαίνει ότι η
έγκριση **λήγει κάθε 7 ημέρες** και ο χρήστης πρέπει να ξανασυνδεθεί.

| | Testing (τώρα) | Production, χωρίς επαλήθευση | Production, με επαλήθευση |
|---|---|---|---|
| Λήξη έγκρισης | κάθε 7 μέρες | ποτέ | ποτέ |
| Οθόνη προειδοποίησης | όχι | «Η Google δεν επαλήθευσε αυτή την εφαρμογή» — μία φορά ανά χρήστη, με «Για προχωρημένους → Συνέχεια» | όχι |
| Όριο χρηστών | 100 test users | 100 μέχρι την επαλήθευση | χωρίς όριο |
| Τι χρειάζεται | τίποτα | ένα κλικ «Publish app» | αίτηση + 3-10 μέρες αναμονή, δωρεάν |

Πρόταση: **Publish app** τώρα (λύνει το 7ήμερο αμέσως) και αίτηση επαλήθευσης
αν/όταν μπουν πολλοί χρήστες.

## Πώς παράγεται το PDF χωρίς το Docs scope

Το `documents` scope δεν χρειάζεται καθόλου:

```
1. Το app ανεβάζει μία φορά το template στο Drive σου (φάκελος «Προσφορές»)
   → είναι αρχείο του app, άρα καλύπτεται από drive.file
2. Το ανοίγεις και το επεξεργάζεσαι κανονικά στο Google Docs όποτε θες
3. Για κάθε προσφορά: export ως .docx → αντικατάσταση placeholders τοπικά
   → upload με μετατροπή σε Google Doc → export PDF → διαγραφή προσωρινού
```

Το ζητούμενο («το πρότυπο να τροποποιείται από το Drive») ισχύει ακριβώς όπως πριν,
χωρίς sensitive scopes και χωρίς εβδομαδιαία επανασύνδεση.
