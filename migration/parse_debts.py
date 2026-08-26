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
from pathlib import Path

AMOUNT = r"([0-9][0-9.]*,[0-9]{2})"
GAP = r"[\s\S]{0,60}?"

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
        re.search(r"Περίοδος\s*(?:Από|Έως)?\s*:?\s*(\d{1,2})\s*/\s*(\d{4})", text)
    ) or from_file_name(name)
    amount = (amount_after(text, r"Σύνολο\s*Εισφ\S*")
              or amount_after(text, r"Καταβλητέες\s*Εισφορές"))
    if amount is None:
        return []
    submission = re.search(r"Αριθμ?\.?\s*Υποβολής\s*:?\s*(\d+)", text)
    label = "ΑΠΔ ΤΕΚΑ" if teka else "ΑΠΔ ΙΚΑ"
    if submission:
        label += f" · υποβολή {submission.group(1)}"
    return [row(kind, period, amount, rf_code(text), label)]


def debt_identity(text: str) -> str:
    """Η ταυτότητα οφειλής ως μία συνεχόμενη σειρά ψηφίων."""
    m = re.search(r"Ταυτότητα\s*Οφειλής\s*:?\s*([0-9][0-9\s.\-]{18,50})", text)
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
    nxt = "Ημερολογιακή|Συνολικό|Ποσό|Ταυτότητα|Ημ/νία|Προσοχή|ΔΟΥ|Τύπος"
    m = re.search(r"Είδος\s*Φόρου\s*:?\s*([\s\S]{1,140}?)\s*(?=" + nxt + r"|$)", text)
    if not m:
        return None
    value = re.sub(r"\s+", " ", m.group(1)).strip().rstrip(",.:")[:90]
    return value or None


def parse_aade(text: str, name: str) -> list[dict]:
    reference = debt_identity(text)
    amount = (amount_after(text, r"Ποσό\s*δόσης")
              or amount_after(text, r"Συνολικό\s*ποσό\s*οφειλής"))
    if amount is None:
        return []
    due = re.search(r"Ποσό\s*δόσης\s*δήλωσης\s*της\s*(\d{1,2}/\d{1,2}/\d{4})", text)
    if not due:
        due = re.search(r"μέχρι\s*τις\s*(\d{1,2}/\d{1,2}/\d{4})", text)
    span = re.search(r"Ημερολογιακή\s*Περίοδος\s*:?\s*(\d{1,2})/(\d{1,2})/(\d{4})", text)
    period = (int(span.group(2)), int(span.group(3))) if span else from_file_name(name)
    return [row(
        "AADE", period, amount, reference,
        tax_kind(text) or "Βεβαιωμένη οφειλή εκτός ρύθμισης",
        due=due.group(1) if due else None,
    )]


def parse_advertising(text: str, name: str) -> list[dict]:
    amount = amount_after(text, r"Εισφορές\s*€") or amount_after(text, r"Εισφορές")
    if amount is None:
        return []
    period = period_of(
        re.search(r"Περίοδος\s*:?\s*(\d{1,2})\s*/\s*(\d{4})", text)
    ) or from_file_name(name)
    cost = amount_after(text, r"Κόστος\s*Διαφήμισης\s*€?")
    label = "Εισφορές διαφήμισης"
    if cost is not None:
        label += f" · κόστος {cost:.2f} €"
    return [row("ADVERTISING", period, amount, rf_code(text), label)]


HEADER = re.compile(r"^\s*(\d{1,3})\s+([A-Za-z0-9]{2,6})\s+(\S.*)$")
DETAIL = re.compile(r"^\s*([Α-ΩΆΈΉΊΌΎΏ]{2})\s+\S")


def numbers_only(line: str) -> list[float] | None:
    trimmed = line.strip()
    if not trimmed or any(c.isalpha() for c in trimmed):
        return None
    values = [v for v in (money(t) for t in trimmed.split()) if v is not None]
    return values or None


def name_of(rest: str) -> str:
    """Επώνυμο και όνομα — πάντα τα δύο πρώτα λεκτικά, όσα κενά κι αν υπάρχουν."""
    return " ".join(rest.strip().split()[:2]).strip()


def read_people(text: str) -> list[dict]:
    people: list[dict] = []
    for line in text.splitlines():
        head = HEADER.match(line)
        if head and head.group(3)[:1].isalpha():
            people.append({"code": head.group(2), "name": name_of(head.group(3)),
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


def parse_payroll(text: str, name: str) -> list[dict]:
    period = period_of(
        re.search(r"Μισθοδοτική\s*Κατάσταση\s*(\d{1,2})\s*/\s*(\d{4})", text)
    ) or from_file_name(name)
    return merge([r for p in read_people(text) for r in rows_for(p, period)])


def parse(text: str, name: str = "") -> list[dict]:
    clean = text.replace(" ", " ")
    if "Μισθοδοτική Κατάσταση" in clean:
        return parse_payroll(clean, name)
    if "Ταυτότητα Οφειλής" in clean or "Σημείωμα για Πληρωμή" in clean:
        return parse_aade(clean, name)
    if "Διαφήμισης" in clean or "ΔΗΜΟΣΙΟΓΡΑΦΙΚΟΣ" in clean:
        return parse_advertising(clean, name)
    if "ΑΠΔ" in clean or "ΑΠΟΔΕΙΚΤΙΚΟΥ ΥΠΟΒΟΛΗΣ" in clean:
        return parse_apd(clean, name)
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
