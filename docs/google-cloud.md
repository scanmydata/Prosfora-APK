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

## Ποια scopes θα ζητήσει το app

Μόνο **`https://www.googleapis.com/auth/drive.file`** — δηλαδή πρόσβαση αποκλειστικά στα
αρχεία που δημιουργεί το ίδιο το app. Δεν βλέπει τίποτε άλλο στο Drive σου.

Αυτό είναι *non-sensitive* scope: δεν χρειάζεται επαλήθευση από την Google, δεν εμφανίζεται
προειδοποιητική οθόνη, και δεν λήγει η έγκριση.

### Γιατί όχι το Docs API scope
Το `documents` scope είναι *sensitive*. Θα δούλευε, αλλά σε κατάσταση Testing η έγκριση
λήγει κάθε 7 μέρες — θα έπρεπε να ξανασυνδέεσαι κάθε βδομάδα. Το αποφεύγουμε:

```
1. Το app ανεβάζει μία φορά το template στο Drive σου (φάκελος «Προσφορές»)
   → είναι αρχείο του app, άρα καλύπτεται από drive.file
2. Το ανοίγεις και το επεξεργάζεσαι κανονικά στο Google Docs όποτε θες
3. Για κάθε προσφορά: export ως .docx → αντικατάσταση placeholders τοπικά
   → upload με μετατροπή σε Google Doc → export PDF → διαγραφή προσωρινού
```

Το ζητούμενο («το πρότυπο να τροποποιείται από το Drive») ισχύει ακριβώς όπως πριν,
χωρίς sensitive scopes και χωρίς εβδομαδιαία επανασύνδεση.
