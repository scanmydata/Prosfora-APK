# Φάση 0 — Καταγραφή AppSheet schema ✅ ΟΛΟΚΛΗΡΩΘΗΚΕ

Πηγή: `window.currentApp().appTemplate` από τον AppSheet editor (πλήρες app definition, όχι screenshots).
Ημερομηνία καταγραφής: 2026-08-20.

## Ταυτότητα app
| | |
|---|---|
| Όνομα | ΠΡΟΣΦΟΡΕΣ |
| App ID | `98768beb-7792-4880-b012-1b9124021bff` |
| Internal name | `Υπολογιστικόφύλλοχωρίςτίτλο-463007400` |
| Owner | adonis.douramanis@gmail.com |
| Plan | FREE · Status: Deployed |
| Locale | el-GR |
| Editor | https://www.appsheet.com/template/AppDef?appId=98768beb-7792-4880-b012-1b9124021bff |

## Πηγή δεδομένων
Ένα Google Sheet, τρία tabs:

| Table | Spreadsheet DocId | Tab (gid) | Header row |
|---|---|---|---|
| Προσφορές | `1KJETbxLzQF2vWms7BIC_Vv7JOPiIE2BeUfOR-G1GhXU` | Προσφορές (gid 0) | 2 |
| Χώροι_έργου | ίδιο | Χώροι_έργου (gid 832136171) | 2 |
| Λίστα_Παρατηρήσεων | ίδιο | Λίστα_Παρατηρήσεων (gid 894261232) | 2 |

Access mode: *as app creator*. Server caching: 5 λεπτά.
**Slices: καμία** (`TableSlices: []`).

Google Doc template για το PDF: `1hgWNL034KwLS9RiaMiQ7pXQXB1bhaKcSsUtQGqZKDF4`

---

## Tables

### 1. Προσφορές (13 στήλες) — κύριος πίνακας
| # | Στήλη | Τύπος | Ιδιότητες |
|---|---|---|---|
| 1 | `_RowNumber` | Number | system, hidden, read-only |
| 2 | `ID_Προσφοράς` | Text | **KEY**, required, hidden, initial = `UNIQUEID()` |
| 3 | `Οδός / Περιοχή` | Text | **LABEL**, searchable |
| 4 | `Ημερομηνία` | Date | searchable |
| 5 | `Είδος` | Text | searchable |
| 6 | `Κατάσταση` | Enum | required — βλ. παρακάτω |
| 7 | `Email` | Email | searchable, `Show_If = ISNOTBLANK([Related Ανάλυση_Χώρων])` |
| 8 | `Γενικό Σύνολο` | Text | searchable — *snapshot* του συνόλου (γράφεται από action) |
| 9 | `Παρατηρήσεις Έργου` | EnumList | `Show_If = ISNOTBLANK([Related Ανάλυση_Χώρων])` |
| 10 | `Αποστολή_Trigger` | Text | searchable — **trigger column** για το email bot |
| 11 | `Related Ανάλυση_Χώρων` | List | **virtual**, read-only, display name `ΧΩΡΟΙ`, `REF_ROWS("Χώροι_έργου", "ID_Προσφοράς")` |
| 12 | `Γενικό Σύνολο Live` | Price | **virtual**, read-only, `=SUM([Related Ανάλυση_Χώρων][Σύνολο Γραμμής])` |
| 13 | `Related Λίστα_Παρατηρήσεων` | List | **virtual**, read-only, `REF_ROWS("Λίστα_Παρατηρήσεων", "ID_Προσφοράς")` |

`Κατάσταση` — Enum με base type Text, τιμές: `Σε επεξεργασία`, `Ολοκληρώθηκε`, `Δημιουργήθηκε`

```
Initial value:
  IF(ISBLANK([Related Ανάλυση_Χώρων]), "Δημιουργήθηκε", "Σε επεξεργασία")

Valid_If:
  IF(
    ISBLANK([Related Ανάλυση_Χώρων]),
    LIST("Δημιουργήθηκε"),
    LIST("Σε επεξεργασία", "Ολοκληρώθηκε")
  )

Show_If:
  ISNOTBLANK([Related Ανάλυση_Χώρων])
```

Δηλαδή: όσο η προσφορά δεν έχει χώρους → κλειδωμένη σε «Δημιουργήθηκε». Μόλις μπει έστω ένας χώρος → επιλογή μεταξύ «Σε επεξεργασία» / «Ολοκληρώθηκε».

`Παρατηρήσεις Έργου` — EnumList (base Text), τιμές:
- `Στην προσφορά δεν περιλαμβάνεται ο ΦΠΑ τιμολογίου.`
- `Η προσφορά περιλαμβάνει την εργασία και τα υλικά.`

### 2. Χώροι_έργου (7 στήλες) — γραμμές ανάλυσης, child του Προσφορές
| # | Στήλη | Τύπος | Ιδιότητες |
|---|---|---|---|
| 1 | `_RowNumber` | Number | system, hidden, read-only |
| 2 | `ID_Χώρου` | Text | **KEY**, required, hidden, initial = `UNIQUEID()` |
| 3 | `ID_Προσφοράς` | **Ref → Προσφορές** | hidden, **IsAPartOf = true** (true parent/child: διαγραφή προσφοράς σβήνει τους χώρους) |
| 4 | `Περιγραφή Χώρου` | Text | **LABEL**, searchable |
| 5 | `Επιφάνεια (τ.μ.)` | Decimal | searchable |
| 6 | `Τιμή Μονάδος` | Price | searchable |
| 7 | `Σύνολο Γραμμής` | Price | app formula `=[Επιφάνεια (τ.μ.)]*[Τιμή Μονάδος]` |

### 3. Λίστα_Παρατηρήσεων (4 στήλες) — child του Προσφορές
| # | Στήλη | Τύπος | Ιδιότητες |
|---|---|---|---|
| 1 | `_RowNumber` | Number | system, hidden, read-only |
| 2 | `ID_Παρατήρησης` | Text | **KEY**, required, initial = `UNIQUEID()` |
| 3 | `ID_Προσφοράς` | **Ref → Προσφορές** | **LABEL**, required, IsAPartOf = false, initial = `UNIQUEID()` ⚠️ |
| 4 | `Κείμενο` | Text | searchable |

⚠️ Το initial value `UNIQUEID()` σε Ref column είναι λάθος στο AppSheet app (παράγει τυχαίο id αντί για το parent id). Στο native δεν το αναπαράγουμε — το ID_Προσφοράς θα γεμίζει από το parent context.

---

## Views (11)
| View | Τύπος | Πηγή | Θέση | Σημειώσεις |
|---|---|---|---|---|
| **Προσφορές** | Deck | Προσφορές | primary nav | Sort: Ημερομηνία ↓ · summary column = `Κατάσταση` · secondary header = `Ημερομηνία` · action bar: Compose Email, ΑΠΟΣΤΟΛΗ ΠΡΟΣΦΟΡΑΣ, Edit, Delete · icon `far fa-scroll` |
| **Ανάλυση** | Table | Χώροι_έργου | ref | Column order: Περιγραφή Χώρου, Επιφάνεια (τ.μ.), Τιμή Μονάδος, Σύνολο Γραμμής |
| **Παρατηρήσεις** | Deck | Προσφορές | ref | auto columns |
| Προσφορές_Detail | Detail (slideshow) | Προσφορές | system | order: Κατάσταση, ID_Προσφοράς, Οδός / Περιοχή, Ημερομηνία, Είδος, Παρατηρήσεις Έργου, Email, Related Ανάλυση_Χώρων, Γενικό Σύνολο Live |
| Προσφορές_Form | Form | Προσφορές | system | order: Οδός / Περιοχή, Ημερομηνία, Είδος, Email, Παρατηρήσεις Έργου, Related Λίστα_Παρατηρήσεων, Κατάσταση |
| Χώροι_έργου_Detail | Detail | Χώροι_έργου | system | |
| Χώροι_έργου_Form | Form | Χώροι_έργου | system | **Form Saved → action `ΓΕΦΥΡΑ`** |
| Χώροι_έργου_Inline | Table | Χώροι_έργου | system | |
| Λίστα_Παρατηρήσεων_Detail | Detail | Λίστα_Παρατηρήσεων | system | |
| Λίστα_Παρατηρήσεων_Form | Form | Λίστα_Παρατηρήσεων | system | |
| Λίστα_Παρατηρήσεων_Inline | Table | Λίστα_Παρατηρήσεων | system | |

---

## Actions

### Φτιαγμένα από τον χρήστη
| Action | Table | Τύπος | Τι κάνει | Condition |
|---|---|---|---|---|
| `Γενικό Σύνολο (ενημέρωση)` | Προσφορές | SET_COLUMN_VALUE | `Γενικό Σύνολο = [Γενικό Σύνολο Live]` | `true` · Do_Not_Display |
| `ΓΕΦΥΡΑ` | Χώροι_έργου | REF_ACTION | Τρέχει το `Γενικό Σύνολο (ενημέρωση)` πάνω στο parent: rows `=LIST([ID_Προσφοράς])` του πίνακα Προσφορές | `true` · Do_Not_Display · καλείται από το Form Saved του Χώροι_έργου_Form |
| `ΑΠΟΣΤΟΛΗ ΠΡΟΣΦΟΡΑΣ` (label «Αποστολή προσφοράς») | Προσφορές | SET_COLUMN_VALUE | `Αποστολή_Trigger = UNIQUEID()` | `AND(NOT(ISBLANK([Email])), [Κατάσταση] = "Ολοκληρώθηκε")` · icon `far fa-envelope` · **NeedsConfirmation** με μήνυμα `CONCATENATE("Θέλετε σίγουρα να στείλετε email στην διεύθυνση ", [Email], …)` |

**Ο μηχανισμός «Γενικό Σύνολο»**: το πραγματικό άθροισμα ζει στο virtual `Γενικό Σύνολο Live`. Επειδή τα virtual columns δεν γράφονται στο Sheet (και το PDF template χρειάζεται πραγματική τιμή), κάθε φορά που αποθηκεύεται χώρος τρέχει `ΓΕΦΥΡΑ` → `Γενικό Σύνολο (ενημέρωση)` που κάνει snapshot την τιμή στο `Γενικό Σύνολο`. **Στο native αυτό εξαφανίζεται εντελώς** — υπολογίζουμε το σύνολο κατευθείαν.

### System-generated
Delete / Edit / Add ανά πίνακα, `View Ref (ID_Προσφοράς)` (NAVIGATE_APP) σε Χώροι_έργου + Λίστα_Παρατηρήσεων, και `Compose Email (Email)` (EMAIL) στο Προσφορές με condition `AND(NOT(ISBLANK([Email])), [Κατάσταση] = "Ολοκληρώθηκε")`.

---

## Automations / Bots (3)

### Bot 1 — `Δημιουργία pdf`
- **Event**: `New event` — Change / ALL_CHANGES στο `Προσφορές_Schema`
- **Filter**:
  ```
  AND(
    [Κατάσταση] = "Ολοκληρώθηκε",
    [_THISROW_BEFORE].[Κατάσταση] <> "Ολοκληρώθηκε"
  )
  ```
- **Process**: `Process for Δημιουργία pdf` → **WaitNode (15 δευτ.)** → task `pdf Task - 1`
- **Task `pdf Task - 1`** (τύπος MakeDoc):
  - Template: Google Doc `1hgWNL034KwLS9RiaMiQ7pXQXB1bhaKcSsUtQGqZKDF4`
  - Content type: PDF · A4 · Portrait · custom margins 1
  - Filename prefix: `=[Οδός / Περιοχή]` (χωρίς timestamp suffix)
  - File store: `_Default` (Google Drive)

### Bot 2 — `Send_Offer_Email` ⭐ αυτό που δεν στέλνεται στο free tier
- **Event**: `Trigger_On_Click` — Change / UPDATES_ONLY στο `Προσφορές_Schema`
- **Filter**: `[_THISROW].[Αποστολή_Trigger] <> [_THISROW_BEFORE].[Αποστολή_Trigger]`
  (δηλαδή: πατάς το κουμπί «Αποστολή προσφοράς» → αλλάζει το `Αποστολή_Trigger` → πυροδοτείται το bot)
- **Process**: `Process for Send_Offer_Email - 1` → task `Trigger_On_Click Task - 1`
- **Task `Trigger_On_Click Task - 1`** (τύπος Email, CustomTemplate):
  - **To**: `=[Email]` · CC: — · BCC: —
  - **Subject**: `Προσφορά ελαιοχρωματισμών <<[Οδός / Περιοχή]>>`
  - **Body**:
    ```
    Καλησπέρα,

    Σας αποστέλλω την προσφορά για το χρωματισμό της <<[Είδος]>> σας.
    Στη διάθεση σας για οποιαδήποτε επιπλέον πληροφορία χρειαστείτε.



    Με εκτίμηση,
    Γιώργος Δουραμάνης
    6945773605
    ```
  - **Attachment**: PDF από το ίδιο Google Doc template, όνομα `ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ <<[Οδός / Περιοχή]>>`, A4 portrait
  - Message channel: System Default (= ο περιορισμός του free tier)

### Bot 3 — `Callback Bot for WAIT`
Εσωτερικό, εξυπηρετεί το WaitNode του bot 1. Δεν αναπαράγεται.

---

## Τι σημαίνει αυτό για το native app

| AppSheet μηχανισμός | Native αντίστοιχο |
|---|---|
| 3 sheets | 3 Firestore collections: `offers`, `spaces`, `notes` |
| `ID_*` = UNIQUEID() | Firestore document IDs |
| Ref + IsAPartOf (Χώροι_έργου) | subcollection `offers/{id}/spaces` ή collection με `offerId` + cascade delete |
| `Γενικό Σύνολο Live` (virtual SUM) | υπολογισμός στο client / aggregate field |
| `Γενικό Σύνολο` + `ΓΕΦΥΡΑ` + `Γενικό Σύνολο (ενημέρωση)` | **καταργούνται** — τεχνητό workaround για virtual columns |
| `Αποστολή_Trigger` + bot filter | **καταργείται** — απευθείας κλήση Cloud Function από το κουμπί |
| Κατάσταση Enum + Valid_If | enum `OfferStatus` + ίδιος κανόνας στο UI |
| MakeDoc PDF από Google Doc template | PDF generation στη Cloud Function (ή Google Docs API με το ίδιο template) |
| Email task | Cloud Function + Resend/Gmail API, ίδιο subject/body/attachment |

---

## Branding & εμφάνιση (UX tab)
| Ρύθμιση | Τιμή |
|---|---|
| Theme | `White - #00e2a2` — **το brand χρώμα είναι #00E2A2** |
| Γραμματοσειρά | Roboto, μέγεθος 16 |
| Footer style | White · Show icon in header: όχι · Show logo on launch: όχι |
| Default start view | Προσφορές |
| Content direction | Left-to-right |
| Logo / Background image | ορισμένα (ανεβασμένα στο AppSheet) |

## Format Rules (6) — από εδώ βγαίνουν τα χρωματιστά dots
| Κανόνας | Στήλη | Condition | Μορφοποίηση |
|---|---|---|---|
| ΟΛΟΚΛΗΡΩΘΗΚΕ | Κατάσταση | `[Κατάσταση]="Ολοκληρώθηκε" AND CONTEXT("ViewType")<>"Form"` | **πράσινο** κείμενο + highlight |
| ΣΕ ΕΠΕΞΕΡΓΑΣΙΑ | Κατάσταση | `[Κατάσταση]="Σε επεξεργασία" AND CONTEXT("ViewType")<>"Form"` | **κόκκινο** κείμενο + highlight |
| EMAIL BUTTON | action ΑΠΟΣΤΟΛΗ ΠΡΟΣΦΟΡΑΣ | `NOT(ISBLANK([Email])) AND [Κατάσταση]="Ολοκληρώθηκε"` | `#FFB300` (κεχριμπαρένιο), small icon |
| DELETE BUTTON | action Delete | — | κόκκινο highlight |
| EDIT | action Edit | — | μπλε highlight |
| ΠΡΟΒΟΛΗ ΣΥΝΟΛΟΥ | Γενικό Σύνολο Live | — | **bold, 1.3× μέγεθος** |

## Security & Sync (Settings tab)
| Ρύθμιση | Τιμή | Σημασία για το native |
|---|---|---|
| Launch offline | ✅ | επιβεβαιώνει offline-first — Room |
| Delayed sync | ✅ | οι αλλαγές δεν πάνε αμέσως στο cloud |
| Sync on start | ❌ | |
| Delta sync | ❌ | |
| Allow search | ❌ | (η αναζήτηση στο native υπάρχει κανονικά) |
| Encrypt local data | ❌ | |
| Require user consent | ✅ | |
| All data is public | ❌ | |
| Geolocation | ❌ | δεν χρειάζεται permission τοποθεσίας |
