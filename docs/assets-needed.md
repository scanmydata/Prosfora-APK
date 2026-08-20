# Τι χρειάζομαι από σένα (assets & πρόσβαση)

Κατάσταση: γεμίζει καθώς προχωράει η καταγραφή. Ό,τι λέει **ΕΚΚΡΕΜΕΙ** το περιμένω από σένα.

---

## 1. PDF template (κρίσιμο)
Το AppSheet παράγει το PDF της προσφοράς από Google Doc:

`https://docs.google.com/document/d/1hgWNL034KwLS9RiaMiQ7pXQXB1bhaKcSsUtQGqZKDF4/edit`

Χρειάζομαι το περιεχόμενο και το layout του για να το αναπαράγω στο native.
**Τι να κάνεις**: File → Download → **Microsoft Word (.docx)** *και* **PDF**, και βάλε τα δύο αρχεία στον φάκελο `assets/pdf-template/` του project.
(Το .docx μου δίνει τα `<<[Στήλη]>>` placeholders, το PDF μου δίνει την τελική εμφάνιση.)

## 2. Λογότυπο & εικόνες app
| Asset | Πού χρησιμοποιείται | Τι χρειάζομαι |
|---|---|---|
| App icon / logo | launcher icon, header | PNG **1024×1024**, διαφανές background αν γίνεται |
| Splash / background image | οθόνη εκκίνησης | PNG/JPG τουλάχιστον **1080×1920** |
| Λογότυπο για το PDF | κεφαλίδα προσφοράς | PNG υψηλής ανάλυσης (αν διαφέρει από το app icon) |

Βάλ' τα στο `assets/branding/`.
*(Σημ.: τώρα το app έχει προσωρινό vector icon που έφτιαξα εγώ.)*

## 3. Χρώματα & γραμματοσειρά — **ΕΚΚΡΕΜΕΙ ΚΑΤΑΓΡΑΦΗ**
Θα τα διαβάσω από το AppSheet (UX → Brand). Αν έχεις συγκεκριμένα εταιρικά χρώματα (hex), πες τα.

## 4. Δεδομένα για migration
Google Sheet: `1KJETbxLzQF2vWms7BIC_Vv7JOPiIE2BeUfOR-G1GhXU`

Δύο επιλογές:
- **Α (απλό)**: File → Download → `.xlsx` και βάλ' το στο `migration/source/`
- **Β (αυτόματο)**: μου δίνεις Google Sheets API service account key — δεν χρειάζεται χειροκίνητο export, μπορώ να ξανατρέξω το migration όποτε θέλω

## 5. Firebase
- Έχεις ήδη Firebase project ή το φτιάχνουμε από το μηδέν;
- Το `google-services.json` πρέπει να μπει στο `app/` (είναι ήδη στο `.gitignore` — δεν ανεβαίνει στο repo)

## 6. Email (Φάση 5)
Το AppSheet έστελνε από: **Γιώργος Δουραμάνης · 6945773605** (υπογραφή στο body).

Χρειάζομαι:
- Ποια διεύθυνση αποστολέα θέλεις να φαίνεται;
- Έχεις δικό σου domain (π.χ. `douramanis.gr`); Αν ναι → **Resend** με verified domain, καθαρή λύση.
- Αν όχι → Gmail API με τον λογαριασμό σου (πιο μπλεγμένο OAuth, αλλά δωρεάν και ο παραλήπτης βλέπει το Gmail σου)

## 7. Signing keystore (για σταθερά updates)
Βλ. [README](../README.md#signing-one-time-setup). Μέχρι να μπει, τα APK υπογράφονται με debug key.

---

## Τι ΔΕΝ χρειάζομαι
- Πρόσβαση στο Google Drive σου — τα υπάρχοντα PDF μένουν εκεί που είναι
- Το AppSheet app να σβηστεί — μένει ζωντανό ως fallback μέχρι να δουλέψει το native (Φάση 7)
