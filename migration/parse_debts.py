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
}


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
    if kind != "PAYROLL":
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


def parse_aade(text: str, name: str) -> list[dict]:
    identity = re.search(r"Ταυτότητα\s*Οφειλής\s*:?\s*([0-9][0-9\s]{18,45})", text)
    reference = "".join(c for c in identity.group(1) if c.isdigit()) if identity else ""
    amount = (amount_after(text, r"Ποσό\s*δόσης")
              or amount_after(text, r"Συνολικό\s*ποσό\s*οφειλής"))
    if amount is None:
        return []
    due = re.search(r"Ποσό\s*δόσης\s*δήλωσης\s*της\s*(\d{1,2}/\d{1,2}/\d{4})", text)
    if not due:
        due = re.search(r"μέχρι\s*τις\s*(\d{1,2}/\d{1,2}/\d{4})", text)
    span = re.search(r"Ημερολογιακή\s*Περίοδος\s*:?\s*(\d{1,2})/(\d{1,2})/(\d{4})", text)
    period = (int(span.group(2)), int(span.group(3))) if span else from_file_name(name)
    tax = re.search(r"Είδος\s*Φόρου\s*:?\s*(.+)", text)
    return [row(
        "AADE", period, amount, reference,
        tax.group(1).strip()[:80] if tax else "Βεβαιωμένη οφειλή εκτός ρύθμισης",
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


def numbers_only(line: str) -> list[float] | None:
    trimmed = line.strip()
    if not trimmed or any(c.isalpha() for c in trimmed):
        return None
    values = [v for v in (money(t) for t in trimmed.split()) if v is not None]
    return values or None


def parse_payroll(text: str, name: str) -> list[dict]:
    period = period_of(
        re.search(r"Μισθοδοτική\s*Κατάσταση\s*(\d{1,2})\s*/\s*(\d{4})", text)
    ) or from_file_name(name)

    people: list[dict] = []
    for line in text.splitlines():
        match = HEADER.match(line)
        if match and match.group(3)[:1].isalpha():
            parts = [p.strip() for p in re.split(r"\s{2,}|\t", match.group(3)) if p.strip()]
            people.append({"code": match.group(2), "name": " ".join(parts[:2]).strip(),
                           "amount": None})
            continue
        values = numbers_only(line)
        if values and len(values) >= 8 and people:
            people[-1]["amount"] = values[-1]

    return [
        row("PAYROLL", period, p["amount"], "", "Πληρωτέο μισθοδοσίας", p["name"], p["code"])
        for p in people
        if p["name"] and (p["amount"] or 0) > 0
    ]


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
