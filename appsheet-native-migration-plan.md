# Πλάνο: AppSheet "ΠΡΟΣΦΟΡΕΣ" → Πλήρες Native Android App

## Σύνοψη
Δεν φτιάχνουμε wrapper. Χτίζουμε πραγματικό native Android app (Kotlin) που αντικαθιστά εντελώς το AppSheet layer, με δικιά του βάση δεδομένων, δικό του CI/CD στο GitHub, migration των υπαρχόντων δεδομένων, και email sending χωρίς τους περιορισμούς του AppSheet free tier.

**Ρεαλιστικό timeline**: 3-6 εβδομάδες part-time δουλειάς μέσω Claude Code, ανάλογα με πολυπλοκότητα των 2-4 πινάκων. Δεν είναι one-shot — θα το τρέξουμε σε φάσεις.

---

## Αρχιτεκτονική

| Στοιχείο | AppSheet (τώρα) | Native (μετά) |
|---|---|---|
| UI | AppSheet generated | Kotlin + Jetpack Compose |
| Database | Google Sheet | Firebase Firestore |
| Auth | AppSheet/Google login | Firebase Auth (Google Sign-In) |
| Αρχεία/φωτό | Google Drive | Google Drive API (μένει ίδιο) |
| Automations/Email | AppSheet workflows (❌ μπλοκαρισμένο σε free tier) | Firebase Cloud Functions + email API |
| Offline sync | AppSheet built-in | Firestore offline persistence (built-in, δωρεάν) |
| Distribution | AppSheet link | GitHub Releases (.apk/.aab) μέσω GitHub Actions |

---

## ΦΑΣΗ 0 — Ανακάλυψη (πριν γράψουμε κώδικα)
Το Claude Code πρέπει πρώτα να καταγράψει ΑΚΡΙΒΩΣ τι υπάρχει, γιατί δεν έχουμε δει το εσωτερικό του app:

1. **Άνοιξε το AppSheet editor** (όχι το app, το editor: appsheet.com → My Apps → Edit)
2. Καταγραφή για κάθε table (2-4 στο σύνολο):
   - Ονόματα στηλών (columns) και τύποι δεδομένων (Text, Number, Date, Image, Ref κ.λπ.)
   - Ποιες στήλες είναι Reference σε άλλο table (foreign keys)
   - Ποια Views υπάρχουν (π.χ. Detail, Form, Table, Gallery) και τι φιλτράρουν
   - Slices (φιλτραρισμένα subsets)
   - Actions (π.χ. "Ολοκληρώθηκε" status button που είδαμε στο screenshot)
   - Automations/Bots (ποια θέλεις να αναπαραχθούν ως Cloud Functions — π.χ. το email που δεν στέλνεται τώρα)
3. Export το raw Google Sheet (File → Download → xlsx) — αυτό θα είναι η πηγή του migration script

➡️ Αυτό το βήμα ΔΕΝ γίνεται 100% από το Claude Code μόνο του, γιατί χρειάζεται να δει κανείς το AppSheet editor UI. Ή μου στέλνεις screenshots από κάθε table/automation, ή τα καταγράφεις εσύ σε ένα αρχείο notes.md.

---

## ΦΑΣΗ 1 — Setup GitHub repo + CI/CD σκελετός
```bash
# Το Claude Code κάνει:
- Δημιουργία νέου repo (ή local git init + σύνδεση με GitHub)
- Android project scaffold (Kotlin, Jetpack Compose, minSdk 26+)
- GitHub Actions workflow: .github/workflows/release.yml
```
Το workflow θα κάνει:
- Trigger σε κάθε push σε `main` (ή σε tag `v*`)
- `./gradlew assembleRelease` + `bundleRelease`
- Signing με keystore αποθηκευμένο ως GitHub Secret (base64)
- Αυτόματο `gh release create` με το .apk/.aab attached
- Αυτό λύνει ακριβώς αυτό που ζήτησες: **κάθε update στο main = νέο GitHub Release αυτόματα**, χωρίς να ξανατρέχεις build χειροκίνητα.

Χρειάζεται από σένα (one-time): keystore file + password, να τα βάλεις ως GitHub Secrets (θα σου δώσω ακριβή εντολή).

---

## ΦΑΣΗ 2 — Firebase setup
```bash
- Firebase project (δωρεάν Spark plan αρκεί για ξεκίνημα)
- Firestore database, collections = τα 2-4 tables σου
- Firebase Auth με Google Sign-In (ίδιος λογαριασμός Google με τώρα)
- Security rules (ποιος μπορεί να διαβάσει/γράψει τι)
```

## ΦΑΣΗ 3 — Migration script (Google Sheet → Firestore)
Python script (τρέχει μία φορά, όχι μέρος του app):
- Διαβάζει το exported .xlsx (ή απευθείας μέσω Sheets API)
- Μετατρέπει κάθε γραμμή σε Firestore document
- Χειρίζεται τα References σωστά (converts σε Firestore document IDs)
- Dry-run mode πρώτα (τυπώνει τι ΘΑ γράψει, χωρίς να γράψει) — για να το ελέγξεις πριν το κάνεις live
- Backup: το original Sheet μένει ανέγγιχτο, οπότε rollback = πάντα δυνατό

## ΦΑΣΗ 4 — Core app: Views + CRUD
Για κάθε table του AppSheet, το αντίστοιχο Compose screen:
- List view (όπως η λίστα "Προσφορές" στο screenshot)
- Detail/Edit view
- Add νέας εγγραφής
- Status actions (π.χ. το πράσινο/κόκκινο dot "Ολοκληρώθηκε"/"Σε επεξεργασία")
- Search (όπως το search bar που είδαμε)

## ΦΑΣΗ 5 — Email automation
Firebase Cloud Function που trigger-άρεται όταν αλλάζει status μιας εγγραφής (ή ό,τι trigger είχε το AppSheet bot), στέλνει email μέσω:
- **Resend.com** (δωρεάν 100 emails/μέρα, πιο απλό setup) ή
- **Gmail API** με το δικό σου λογαριασμό (δωρεάν, αλλά πιο πολύπλοκο OAuth setup)
Θα σου προτείνω Resend εκτός αν έχεις λόγο να θες Gmail συγκεκριμένα.

## ΦΑΣΗ 6 — Google Drive integration
Αν το AppSheet app σου συνδέει φωτογραφίες/αρχεία με Drive:
- Drive API integration στο native app (ίδιος μηχανισμός, δικό σου OAuth client)
- Τα υπάρχοντα αρχεία στο Drive ΔΕΝ μετακινούνται — απλά το νέο app θα τα διαβάζει/γράφει από το ίδιο σημείο

## ΦΑΣΗ 7 — Testing + πρώτο release
- Παράλληλη λειτουργία: το AppSheet app μένει ζωντανό μερικές μέρες ως fallback ενώ δοκιμάζεις το native
- Πρώτο GitHub Release (v1.0.0) — install στο κινητό σου, πραγματικός έλεγχος με πραγματικά δεδομένα

---

## Τι χρειάζομαι από σένα ΤΩΡΑ για να ξεκινήσουμε τη Φάση 0
1. Ονόματα και των 2-4 tables/views
2. Screenshot ή περιγραφή των στηλών κάθε table (ή export του Sheet αν είναι εύκολο)
3. Ποιο ήταν ακριβώς το automation/bot που ήθελες για email (ποιο trigger, τι έστελνε, σε ποιον)
4. Αν έχεις ήδη λογαριασμό GitHub (και όνομα repo που προτιμάς) και αν έχεις Firebase account ή θα το φτιάξουμε από το μηδέν
