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

## Publishing status: **In production** ✅

Έγινε στις 22/8/2026. Η έγκριση OAuth **δεν λήγει πλέον** — τέλος το 7ήμερο.

| Πεδίο | Τιμή |
|---|---|
| App name | `Prosfora tovapsimo.gr` |
| App logo | το πράσινο τρίγωνο, 120×120 |
| Home page | https://scanmydata.github.io/Prosfora-APK/ |
| Privacy policy | https://scanmydata.github.io/Prosfora-APK/privacy-policy.html |
| Authorised domain | `scanmydata.github.io` |

Η πολιτική απορρήτου φιλοξενείται σε GitHub Pages από τον φάκελο `docs/`.
Αν κάποτε μπει σελίδα στο tovapsimo.gr, αλλάζουμε το URL και το authorised domain.

### Τι σημαίνει «απαιτείται επαλήθευση»
Το console δείχνει προειδοποίηση, και είναι αναμενόμενη: με sensitive scopes και
λογότυπο, χωρίς επαλήθευση ισχύουν δύο περιορισμοί —

1. Στο consent window δεν εμφανίζεται το λογότυπο/branding
2. Ο χρήστης βλέπει μία φορά «Η Google δεν επαλήθευσε αυτή την εφαρμογή»
   (Για προχωρημένους → Συνέχεια)

Η εφαρμογή λειτουργεί κανονικά και η έγκριση δεν λήγει. Όριο 100 χρήστες.
Η επαλήθευση είναι δωρεάν (3-10 μέρες) και **δεν** απαιτεί έλεγχο ασφαλείας,
επειδή δεν ζητάμε κανένα restricted scope.

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
