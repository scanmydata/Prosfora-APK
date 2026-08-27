#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Reference implementation του DebtParser — η ίδια λογική σε Kotlin ζει στο
`gr.prosfora.app.debt.DebtParser`.

Υπάρχει για να ελέγχονται τα μοτίβα πάνω σε **πραγματικά** παραστατικά, χωρίς
συσκευή και χωρίς να ανεβεί τίποτα στο Drive:

    python migration/parse_debts.py ~/Downloads/ΑΠΔ*.pdf
    python migration/parse_debts.py --text dump.txt --name "ΑΠΔ 2026 ΙΟΥΝΙΟΣ.pdf"

Στην εφαρμογή το κείμενο έρχεται από το Drive (PDF → Google Doc → text, με OCR
όπου χρειάζεται). Εδώ βγαίνει με pypdf, που δίνει διαφορετική σειρά γραμμών —
γι' αυτό ακριβώς όλα τα μοτίβα αγκυρώνονται σε ετικέτα και όχι σε θέση.
"""
from __future__ import annotations

import argparse
import calendar
import hashlib
import re
import sys
import unicodedata
from pathlib import Path

AMOUNT = r"([0-9][0-9.]*,[0-9]{2})"
GAP = r"[\s\S]{0,60}?"

# Λατινικά κεφαλαία που μοιράζονται γλυφο με ελληνικά. Το OCR διαλέγει το ένα
# ή το άλλο χωρίς κανόνα, οπότε όλα γυρίζουν στο ελληνικό.
LOOKALIKE = str.maketrans({
    "A": "Α", "B": "Β", "E": "Ε", "Z": "Ζ", "H": "Η", "I": "Ι", "K": "Κ",
    "M": "Μ", "N": "Ν", "O": "Ο", "P": "Ρ", "T": "Τ", "Y": "Υ", "X": "Χ",
})

# Ψηφία που το OCR βάζει μέσα σε λέξεις, στη θέση του γράμματος
DIGIT_TWINS = {"Ο": "Ο0", "Ι": "Ι1"}


def normalize(text: str) -> str:
    """Το κείμενο σε μορφή που δεν εξαρτάται από την ποιότητα του OCR.

    Τόνοι, τελικό σίγμα και πεζά/κεφαλαία φεύγουν, και τα λατινικά δίδυμα
    γυρίζουν σε ελληνικά. Χωρίς αυτό, ένα «Ποσό δόσης» που το OCR το έβγαλε
    «ΠOΣO ΔOΣΗΣ» με λατινικό O δεν ταιριάζει με τίποτα.
    """
    plain = text.replace("\u00a0", " ").replace("\xa0", " ")
    decomposed = unicodedata.normalize("NFD", plain)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    return stripped.upper().replace("\u03c2", "\u03a3").translate(LOOKALIKE)


SLACK_FROM = 7   # από πόσα γράμματα και πάνω συγχωρείται ένα λάθος
GLUE = r"\s*"    # ανάμεσα σε κάθε δύο γράμματα χωράει όσο κενό θέλει η μηχανή


def _char(ch: str) -> str:
    if ch in DIGIT_TWINS:
        return "[" + DIGIT_TWINS[ch] + "]"
    return ch if ch.isalnum() else re.escape(ch)


def anchor(text: str) -> str:
    """Ένα σταθερό λεκτικό ως μοτίβο ανεκτικό στο OCR.

    Τα γράμματα κολλάνε με ``\s*`` γιατί το OCR σπάει λέξεις, και σε ετικέτες
    από ``SLACK_FROM`` γράμματα και πάνω επιτρέπεται **ένα** λάθος γράμμα: μια
    ετικέτα δώδεκα γραμμάτων έχει δώδεκα ευκαιρίες να χαλάσει, και μία αρκούσε
    για να χαθεί ολόκληρη η περίοδος της οφειλής.
    """
    parts = [_char(c) for c in normalize(text) if c != " "]
    if len(parts) < SLACK_FROM:
        return GLUE.join(parts)
    return "(?:" + "|".join(
        GLUE.join("." if at == free else part for at, part in enumerate(parts))
        for free in range(len(parts))
    ) + ")"

MONTHS = [
    "ΙΑΝΟΥΑΡ", "ΦΕΒΡΟΥΑΡ", "ΜΑΡΤ", "ΑΠΡΙΛ", "ΜΑΙ", "ΙΟΥΝ",
    "ΙΟΥΛ", "ΑΥΓΟΥΣΤ", "ΣΕΠΤΕΜΒΡ", "ΟΚΤΩΒΡ", "ΝΟΕΜΒΡ", "ΔΕΚΕΜΒΡ",
]

AGENCY = {
    "IKA": "ΙΚΑ & ΤΕΚΑ",
    "TEKA": "ΙΚΑ & ΤΕΚΑ",
    "AADE": "ΑΑΔΕ",
    "ADVERTISING": "Διαφημιστικά τέλη",
    "PAYROLL": "Μισθοδοσία",
    "PAYROLL_BONUS": "Μισθοδοσία",
}

# Οι κωδικοί αποδοχών της μισθοδοτικής κατάστασης, όπως τυπώνονται
PAY_TYPES = {
    "ΤΑ": "Τακτικές αποδοχές",
    "ΕΑ": "Επίδομα αδείας",
    "ΑΛ": "Άδεια ληφθείσα",
    "ΜΛ": "Άδεια μη ληφθείσα",
    "ΔΠ": "Δώρο Πάσχα",
    "ΔΧ": "Δώρο Χριστουγέννων",
}
BONUS_TYPES = {"ΔΠ", "ΔΧ"}
ANY_AMOUNT = re.compile(r"[0-9][0-9.]*,[0-9]{2}")


def money(raw: str) -> float | None:
    token = raw.strip().strip("€.:").replace(" ", "")
    if not token or not any(c.isdigit() for c in token):
        return None
    normalized = token.replace(".", "").replace(",", ".") if "," in token else token
    try:
        return float(normalized)
    except ValueError:
        return None


def amount_after(text: str, label: str) -> float | None:
    match = re.search(label + GAP + AMOUNT, text)
    return money(match.group(match.re.groups)) if match else None


def rf_code(text: str) -> str:
    match = re.search(r"RF\d{2}[0-9A-Z ]{10,40}", text)
    if not match:
        return ""
    return "".join(match.group(0).split())[:25]


def from_file_name(name: str) -> tuple[int, int]:
    upper = name.upper()
    year = re.search(r"(20\d{2})", upper)
    if not year:
        return (0, 0)
    month = next((i + 1 for i, m in enumerate(MONTHS) if m in upper), 0)
    return (month, int(year.group(1)))


def period_of(match, month_group=1, year_group=2) -> tuple[int, int] | None:
    if not match:
        return None
    month = int(match.group(month_group))
    year = int(match.group(year_group))
    return (month, year) if 1 <= month <= 12 else None


def default_due(kind: str, month: int, year: int) -> str | None:
    if not (1 <= month <= 12) or year <= 0:
        return None
    if not kind.startswith("PAYROLL"):
        month += 1
        if month == 13:
            month, year = 1, year + 1
    return f"{calendar.monthrange(year, month)[1]}/{month}/{year}"


def debt_id(kind: str, month: int, year: int, reference: str, person: str) -> str:
    seed = "|".join([kind, str(year), str(month), reference, person])
    return "debt-" + hashlib.sha1(seed.encode("utf-8")).hexdigest()[:16]


def row(kind, period, amount, reference="", description="", person="", code="", due=None):
    month, year = period
    return {
        "id": debt_id(kind, month, year, reference, person),
        "kind": kind,
        "agency": AGENCY[kind],
        "period": f"{month}/{year}" if month else "—",
        "due": due or default_due(kind, month, year),
        "amount": amount,
        "reference": reference,
        "description": description,
        "person": person,
        "code": code,
    }


# ------------------------------------------------------------- αναγνώστες ---

def parse_apd(text: str, name: str) -> list[dict]:
    teka = "ΤΕΚΑ" in text or "ΤΕΚΑ" in name.upper()
    kind = "TEKA" if teka else "IKA"
    period = period_of(
        re.search(anchor("Περίοδος") + r"\s*(?:ΑΠΟ|ΕΩΣ)?\s*:?\s*(\d{1,2})\s*/\s*(\d{4})", text)
    ) or from_file_name(name)
    amount = (amount_after(text, anchor("Σύνολο Εισφ") + r"\S*")
              or amount_after(text, anchor("Καταβλητέες Εισφορές")))
    if amount is None:
        return []
    submission = re.search(anchor("Αριθμ") + r"\.?\s*" + anchor("Υποβολής") + r"\s*:?\s*(\d+)", text)
    label = "ΑΠΔ ΤΕΚΑ" if teka else "ΑΠΔ ΙΚΑ"
    if submission:
        label += f" · υποβολή {submission.group(1)}"
    return [row(kind, period, amount, rf_code(text), label)]


def debt_identity(text: str) -> str:
    """Η ταυτότητα οφειλής ως μία συνεχόμενη σειρά ψηφίων."""
    m = re.search(anchor("Ταυτότητα Οφειλής") + r"\s*:?\s*([0-9][0-9\s.\-]{18,50})", text)
    if m:
        digits = "".join(c for c in m.group(1) if c.isdigit())
        if len(digits) >= 15:
            return digits
    for m in re.finditer(r"(?<![0-9])([0-9][0-9\s.\-]{22,45}[0-9])(?![0-9])", text):
        digits = "".join(c for c in m.group(1) if c.isdigit())
        if 20 <= len(digits) <= 32:
            return digits
    return ""


def tax_kind(text: str) -> str | None:
    """Το είδος φόρου, που σπάει σε δύο γραμμές στο έντυπο."""
    nxt = "|".join(anchor(w) for w in (
        "Ημερολογιακή", "Συνολικό", "Ποσό", "Ταυτότητα", "Ημ/νία",
        "Προσοχή", "ΔΟΥ", "Τύπος",
    ))
    m = re.search(anchor("Είδος Φόρου") + r"\s*:?\s*([\s\S]{1,140}?)\s*(?=" + nxt + r"|$)", text)
    if not m:
        return None
    value = re.sub(r"\s+", " ", m.group(1)).strip().rstrip(",.:")[:90]
    return value or None


DATE_RANGE = re.compile(r"(\d{1,2})/(\d{1,2})/(\d{4})\s*[-\u2010-\u2015]\s*\d{1,2}/\d{1,2}/\d{4}")


def aade_period(text: str, name: str, due: str | None) -> tuple[int, int]:
    """Ο μήνας αναφοράς, με σειρά αξιοπιστίας.

    Η ετικέτα «Ημερολογιακή Περίοδος» είναι η ακριβέστερη αλλά και η πιο
    μακριά — δώδεκα γράμματα, δώδεκα ευκαιρίες να τη χαλάσει το OCR. Το εύρος
    ημερομηνιών από κάτω δεν χρειάζεται καθόλου ετικέτα: κανένα άλλο σημείο
    του εντύπου δεν έχει δύο ημερομηνίες με παύλα ανάμεσα.

    Τελευταία λύση, το **έτος** της προθεσμίας. Ο μήνας μένει άγνωστος αντί να
    μαντευτεί, αλλά η οφειλή πέφτει στη σωστή χρονιά — αλλιώς έμενε αόρατη.
    """
    span = re.search(
        anchor("Ημερολογιακή Περίοδος") + r"\s*:?\s*(\d{1,2})/(\d{1,2})/(\d{4})", text
    )
    if span:
        return (int(span.group(2)), int(span.group(3)))

    rng = DATE_RANGE.search(text)
    if rng:
        return (int(rng.group(2)), int(rng.group(3)))

    from_name = from_file_name(name)
    if from_name[1] > 0:
        return from_name

    if due:
        return (0, int(due.split("/")[2]))
    return (0, 0)


def parse_aade(text: str, name: str) -> list[dict]:
    reference = debt_identity(text)
    amount = (amount_after(text, anchor("Ποσό δόσης"))
              or amount_after(text, anchor("Συνολικό ποσό οφειλής")))
    if amount is None:
        return []
    due = re.search(anchor("Ποσό δόσης δήλωσης της") + r"\s*(\d{1,2}/\d{1,2}/\d{4})", text)
    if not due:
        due = re.search(anchor("μέχρι τις") + r"\s*(\d{1,2}/\d{1,2}/\d{4})", text)
    period = aade_period(text, name, due.group(1) if due else None)
    return [row(
        "AADE", period, amount, reference,
        tax_kind(text) or "Βεβαιωμένη οφειλή εκτός ρύθμισης",
        due=due.group(1) if due else None,
    )]


def parse_advertising(text: str, name: str) -> list[dict]:
    amount = (amount_after(text, anchor("Εισφορές") + r"\s*\u20ac")
              or amount_after(text, anchor("Εισφορές")))
    if amount is None:
        return []
    period = period_of(
        re.search(anchor("Περίοδος") + r"\s*:?\s*(\d{1,2})\s*/\s*(\d{4})", text)
    ) or from_file_name(name)
    cost = amount_after(text, anchor("Κόστος Διαφήμισης") + r"\s*\u20ac?")
    label = "Εισφορές διαφήμισης"
    if cost is not None:
        label += f" · κόστος {cost:.2f} €"
    return [row("ADVERTISING", period, amount, rf_code(text), label)]


# Το κείμενο έχει ήδη κανονικοποιηθεί: κεφαλαία, χωρίς τόνους, χωρίς λατινικά
HEADER = re.compile(r"^\s*(\d{1,3})\s+([Α-Ω0-9]{2,6})\s+(\S.*)$")
DETAIL = re.compile(r"^\s*([Α-Ω]{2})\s+\S")


def numbers_only(line: str) -> list[float] | None:
    trimmed = line.strip()
    if not trimmed or any(c.isalpha() for c in trimmed):
        return None
    values = [v for v in (money(t) for t in trimmed.split()) if v is not None]
    return values or None


def name_of(rest: str) -> str:
    """Επώνυμο και όνομα — πάντα τα δύο πρώτα λεκτικά, όσα κενά κι αν υπάρχουν."""
    return " ".join(rest.strip().split()[:2]).strip()


def read_people(raw: str, norm: str) -> list[dict]:
    """Οι εργαζόμενοι, με τα ονόματα όπως ακριβώς τυπώνονται.

    Το ταίριασμα γίνεται στο κανονικοποιημένο κείμενο —εκεί δουλεύουν τα
    μοτίβα— αλλά το όνομα κόβεται από το αυθεντικό στην ίδια θέση. Η
    ``normalize`` δεν αλλάζει μήκος, οπότε οι θέσεις συμπίπτουν. Χωρίς αυτό
    ένα «BUTT HURARA» θα γινόταν «ΒUΤΤ ΗURΑRΑ»: το OCR θα διορθωνόταν και το
    όνομα θα χαλούσε.
    """
    people: list[dict] = []
    for line, plain in zip(norm.splitlines(), raw.splitlines()):
        head = HEADER.match(line)
        if head and head.group(3)[:1].isalpha():
            start, end = head.span(3)
            people.append({"code": head.group(2), "name": name_of(plain[start:end]),
                           "details": [], "payable": None})
            continue
        if not people:
            continue
        person = people[-1]

        detail = DETAIL.match(line)
        if detail:
            found = ANY_AMOUNT.search(line)
            gross = money(found.group(0)) if found else None
            if gross:
                person["details"].append((detail.group(1), gross))
            continue

        values = numbers_only(line)
        if values and len(values) >= 8:
            person["payable"] = values[-1]
    return people


def describe(details) -> str:
    if not details:
        return "Πληρωτέο μισθοδοσίας"
    grouped: dict[str, float] = {}
    for code, gross in details:
        grouped[code] = grouped.get(code, 0.0) + gross
    return " · ".join(f"{PAY_TYPES.get(c, c)} {g:,.2f} €".replace(",", "\x00")
                      .replace(".", ",").replace("\x00", ".")
                      for c, g in grouped.items())


def rows_for(person: dict, period) -> list[dict]:
    payable = person["payable"]
    if not payable or payable <= 0 or not person["name"]:
        return []

    bonuses = [d for d in person["details"] if d[0] in BONUS_TYPES]
    regular = [d for d in person["details"] if d[0] not in BONUS_TYPES]
    total_gross = sum(g for _, g in person["details"])

    if not bonuses or total_gross <= 0:
        return [row("PAYROLL", period, payable, "", describe(regular),
                    person["name"], person["code"])]

    out, left = [], payable
    for code, gross in bonuses:
        share = round(payable * gross / total_gross, 2)
        left -= share
        out.append(row("PAYROLL_BONUS", period, share, "",
                       f"{PAY_TYPES.get(code, code)} (αναλογικά)",
                       person["name"], person["code"]))
    if round(left, 2) > 0:
        out.append(row("PAYROLL", period, round(left, 2), "", describe(regular),
                       person["name"], person["code"]))
    return out


def merge(rows: list[dict]) -> list[dict]:
    """Ο ίδιος εργαζόμενος δύο φορές γίνεται μία πληρωμή με το άθροισμα."""
    out: dict[str, dict] = {}
    for r in rows:
        existing = out.get(r["id"])
        if existing is None:
            out[r["id"]] = r
        else:
            existing["amount"] = round(existing["amount"] + r["amount"], 2)
            if r["description"] not in existing["description"]:
                existing["description"] += " · " + r["description"]
    return list(out.values())


def parse_payroll(raw: str, norm: str, name: str) -> list[dict]:
    period = period_of(
        re.search(anchor("Μισθοδοτική Κατάσταση") + r"\s*(\d{1,2})\s*/\s*(\d{4})", norm)
    ) or from_file_name(name)
    return merge([r for p in read_people(raw, norm) for r in rows_for(p, period)])


def looks_like(clean: str, *words: str) -> bool:
    return any(re.search(anchor(w), clean) for w in words)


def parse(text: str, name: str = "") -> list[dict]:
    clean = normalize(text)
    upper = normalize(name)
    if looks_like(clean, "Μισθοδοτική Κατάσταση"):
        return parse_payroll(text, clean, upper)
    if looks_like(clean, "Ταυτότητα Οφειλής", "Σημείωμα για Πληρωμή"):
        return parse_aade(clean, upper)
    if looks_like(clean, "Διαφήμισης", "ΔΗΜΟΣΙΟΓΡΑΦΙΚΟΣ"):
        return parse_advertising(clean, upper)
    if looks_like(clean, "ΑΠΔ", "ΑΠΟΔΕΙΚΤΙΚΟΥ ΥΠΟΒΟΛΗΣ"):
        return parse_apd(clean, upper)
    # Τελευταία γραμμή άμυνας: το σημείωμα της ΑΑΔΕ είναι το μόνο έντυπο που
    # φτάνει πάντα μέσω OCR, και η ταυτότητα οφειλής του είναι αλάνθαστο
    # αποτύπωμα — τίποτε άλλο στο έντυπο δεν έχει τόσα συνεχόμενα ψηφία
    if debt_identity(clean):
        return parse_aade(clean, upper)
    return []


# ------------------------------------------------------------------ CLI ---

def text_of(path: Path) -> str:
    if path.suffix.lower() != ".pdf":
        return path.read_text(encoding="utf-8")
    import pypdf

    reader = pypdf.PdfReader(str(path))
    return "\n".join((page.extract_text() or "") for page in reader.pages)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="*", help="παραστατικά .pdf ή .txt")
    parser.add_argument("--text", help="έτοιμο κείμενο αντί για αρχείο")
    parser.add_argument("--name", default="", help="όνομα αρχείου, για τον μήνα")
    args = parser.parse_args()

    jobs: list[tuple[str, str]] = []
    if args.text:
        jobs.append((args.name or args.text, Path(args.text).read_text(encoding="utf-8")))
    for raw in args.files:
        path = Path(raw)
        jobs.append((path.name, text_of(path)))

    if not jobs:
        parser.error("δώσε αρχεία ή --text")

    total = 0
    for name, text in jobs:
        rows = parse(text, name)
        print("=" * 72)
        print(name)
        if not text.strip():
            print("  (κενό κείμενο — το PDF θέλει OCR)")
        if not rows:
            print("  δεν αναγνωρίστηκε")
            continue
        for r in rows:
            total += 1
            person = f"  {r['person']}" if r["person"] else ""
            print(f"  [{r['agency']:<18}] {r['kind']:<11} {r['period']:>8}"
                  f"  λήξη {str(r['due']):>10}  {r['amount']:>10,.2f} €{person}")
            if r["reference"]:
                print(f"       ταυτότητα/RF: {r['reference']}")
            if r["description"]:
                print(f"       {r['description']}")
    print("=" * 72)
    print(f"σύνολο γραμμών: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
