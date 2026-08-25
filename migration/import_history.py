#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Μετατρέπει τον φάκελο «ΕΠΙΜΕΤΡΗΣΕΙΣ ΔΟΥΛΕΙΕΣ» σε αρχείο εισαγωγής για την εφαρμογή.

    python migration/import_history.py --folder "C:\\...\\ΕΠΙΜΕΤΡΗΣΕΙΣ ΔΟΥΛΕΙΕΣ" \\
        --out ιστορικό.json

Μετά, στο κινητό: Ρυθμίσεις → Δεδομένα → «Εισαγωγή ιστορικού» και διάλεξε το αρχείο.

Γιατί εδώ και όχι μέσα στην εφαρμογή: τα αρχεία είναι .xls του Excel 97 (BIFF),
που στο Android θα απαιτούσαν ολόκληρο το Apache POI — δεκάδες MB για μια
δουλειά που γίνεται μία φορά. Ο υπολογιστής τα διαβάζει, το κινητό παίρνει ένα
καθαρό JSON.

Τα IDs βγαίνουν από τη διαδρομή του αρχείου, οπότε η εισαγωγή είναι idempotent:
αν ξανατρέξει, οι ίδιες προσφορές ενημερώνονται αντί να διπλασιαστούν.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
import warnings

warnings.filterwarnings("ignore")

EPOCH = dt.date(1970, 1, 1)

# Οι επικεφαλίδες του πίνακα, σε όλες τις παραλλαγές που εμφανίζονται στο αρχείο
HEADER_FIRST = ("ΠΕΡΙΓΡΑΦΗ",)
HEADER_SECOND = ("ΕΠΙΦΑΝΕΙΑ", "ΕΜΒΑΔΟΝ", "Τ.Μ")

# Γραμμές που είναι αθροίσματα, όχι χώροι
TOTAL_ROW = ("ΣΥΝΟΛΟ", "ΓΕΝΙΚΟ ΣΥΝΟΛΟ", "ΣΥΝΟΛΟ ")

# Επικεφαλίδες που τερματίζουν ένα μπλοκ κειμένου
BLOCK_STOPS = (
    "ΤΡΟΠΟΣ ΠΛΗΡΩΜΗΣ",
    "ΔΕΙΓΜΑΤΑ ΕΡΓΑΣΙΩΝ",
    "Ο ΕΡΓΟΛΗΠΤΗΣ",
    "ΠΑΡΑΤΗΡΗΣΕΙΣ",
)

DATE = re.compile(r"(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{2,4})")
VALID_UNTIL = re.compile(r"ισχύει\s+έως\s+(.+)$", re.IGNORECASE)
ON_STREET = re.compile(r"\s*ΕΠΙ\s+ΤΗΣ\s+ΟΔΟΥ\s*$", re.IGNORECASE)
OFFER_TITLE = re.compile(
    r"^(?:ΠΡΟΣΦΟΡΑ|ΕΠΙΜΕΤΡΗΣΗ)\s+(?:ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ|ΕΡΓΑΣΙΩΝ)?(?:\s+ΓΙΑ)?\s*[,:]?\s*",
    re.IGNORECASE,
)
# Πού τελειώνει το είδος και αρχίζει η διεύθυνση
STREET_SPLIT = re.compile(r"ΕΠΙ\s+ΤΗΣ\s+ΟΔΟΥ|ΔΙΕΥΘΥΝΣΗ\s*:?|ΟΔΟΣ\s*:", re.IGNORECASE)
# «… , Αθήνα 8/3/2010» στο τέλος της διεύθυνσης δεν είναι διεύθυνση
TRAILING_DATE = re.compile(
    r"[,\s]*(?:ΑΘΗΝΑ)?\s*\d{1,2}\s*[/.\-]\s*\d{1,2}\s*[/.\-]\s*\d{2,4}\s*$",
    re.IGNORECASE,
)


def tidy(value: str) -> str:
    value = TRAILING_DATE.sub("", value)
    return value.strip(" ,:-·	")


# ----------------------------------------------------------- ανάγνωση φύλλου ---

def read_rows(path: str) -> list[list] | None:
    """Το φύλλο ως πίνακας τιμών. None αν δεν διαβάζεται."""
    lower = path.lower()
    try:
        if lower.endswith(".xls"):
            import xlrd

            book = xlrd.open_workbook(path)
            sheet = book.sheet_by_index(0)

            def value(r, c):
                # Τα κελιά ημερομηνίας είναι σειριακοί αριθμοί· χωρίς μετατροπή
                # καταλήγουν στη διεύθυνση ως «44961»
                if sheet.cell_type(r, c) == xlrd.XL_CELL_DATE:
                    y, m, d = xlrd.xldate_as_tuple(sheet.cell_value(r, c), book.datemode)[:3]
                    return f"{d}/{m}/{y}" if y else ""
                return sheet.cell_value(r, c)

            return [
                [value(r, c) for c in range(sheet.ncols)]
                for r in range(sheet.nrows)
            ]
        if lower.endswith((".xlsx", ".xlsm")):
            import openpyxl

            sheet = openpyxl.load_workbook(path, data_only=True).worksheets[0]
            return [list(row) for row in sheet.iter_rows(values_only=True)]
    except Exception:
        return None
    return None


def text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, float):
        return str(int(value)) if value == int(value) else str(value)
    return str(value).strip()


def number(value):
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    raw = text(value).replace("€", "").replace(".", "").replace(",", ".").strip()
    try:
        return float(raw)
    except ValueError:
        return None


def cell(rows, r, c) -> str:
    if r >= len(rows) or c >= len(rows[r]):
        return ""
    return text(rows[r][c])


def row_text(rows, r) -> str:
    """Το πρώτο μη κενό κελί της γραμμής — εκεί ζει το κείμενο των μπλοκ."""
    for c in range(min(len(rows[r]), 4)):
        value = text(rows[r][c])
        if value:
            return value
    return ""


def find_header(rows) -> int | None:
    for r in range(min(len(rows), 30)):
        joined = " | ".join(text(v).upper() for v in rows[r][:8])
        if any(h in joined for h in HEADER_FIRST) and any(h in joined for h in HEADER_SECOND):
            return r
    return None


# ------------------------------------------------------------------ κομμάτια ---

def parse_spaces(rows, header: int) -> list[dict]:
    spaces = []
    for r in range(header + 1, len(rows)):
        name = cell(rows, r, 0)
        upper = name.upper().strip()
        if upper.startswith("ΓΕΝΙΚΟ ΣΥΝΟΛΟ"):
            break
        if upper.startswith("ΠΑΡΑΤΗΡΗΣ") or upper.startswith("ΤΡΟΠΟΣ ΠΛΗΡΩΜΗΣ"):
            break
        if not name or upper in TOTAL_ROW or upper.startswith("ΣΥΝΟΛΟ"):
            continue
        area = number(rows[r][1] if len(rows[r]) > 1 else None)
        price = number(rows[r][2] if len(rows[r]) > 2 else None)
        if area is None or price is None:
            continue
        spaces.append({"description": name, "area": area, "unitPrice": price})
    return spaces


def parse_block(rows, title: str) -> list[str]:
    """Οι γραμμές κάτω από μια επικεφαλίδα, μέχρι την επόμενη επικεφαλίδα."""
    start = None
    for r in range(len(rows)):
        if row_text(rows, r).upper().startswith(title):
            start = r
            break
    if start is None:
        return []

    lines = []
    for r in range(start + 1, len(rows)):
        line = row_text(rows, r)
        upper = line.upper().rstrip(" :")
        if any(upper.startswith(stop) for stop in BLOCK_STOPS):
            break
        if line:
            lines.append(line)
    return lines


def parse_head(rows, header: int) -> tuple[str, str, dt.date | None]:
    """Είδος, διεύθυνση και ημερομηνία από το μπλοκ πάνω από τον πίνακα."""
    lines, when = [], None
    for r in range(header):
        for c in range(min(len(rows[r]), 6)):
            value = text(rows[r][c])
            if not value:
                continue
            match = DATE.search(value)
            # Ο τίτλος ζει στις πρώτες στήλες· δεξιότερα υπάρχουν επικεφαλίδες
            # δεύτερου πίνακα που δεν είναι μέρος της διεύθυνσης
            if c > 2 and not match:
                continue
            if match and when is None:
                day, month, year = (int(g) for g in match.groups())
                if year < 100:
                    year += 2000 if year < 70 else 1900
                try:
                    when = dt.date(year, month, day)
                except ValueError:
                    pass
            # Η γραμμή «ΑΘΗΝΑ 18/10/2024» είναι μόνο ημερομηνία, όχι τίτλος
            if match and not TRAILING_DATE.sub("", value).strip(" ,:-"):
                continue
            lines.append(value)

    # Οι γραμμές ενώνονται πρώτα: το «ΕΠΙ ΤΗΣ ΟΔΟΥ» άλλοτε κλείνει τη γραμμή και
    # άλλοτε βρίσκεται στη μέση, με τη διεύθυνση να ακολουθεί στην ίδια.
    joined = ", ".join(lines)
    joined = OFFER_TITLE.sub("", joined, count=1)

    split = STREET_SPLIT.search(joined)
    if split:
        kind = tidy(joined[: split.start()])
        address = tidy(joined[split.end():])
    else:
        kind, address = "", tidy(joined)

    return kind, address, when


def guess_status(relpath: str) -> str:
    """Οι φάκελοι λένε την ιστορία: «DONE» = έγινε, «ΠΡΟΣΦΟΡΕΣ» = έμεινε προσφορά."""
    parts = [p.upper() for p in relpath.split(os.sep)[:-1]]
    for part in parts:
        if "DONE" in part:
            return "COMPLETED"
        if "ΠΡΟΣΦΟΡΕΣ" in part:
            return "IN_PROGRESS"
    return "COMPLETED"


def guess_year(relpath: str) -> int | None:
    for part in relpath.split(os.sep):
        match = re.fullmatch(r"(20\d{2})", part.strip())
        if match:
            return int(match.group(1))
    return None


def parse_date_text(value: str) -> dt.date | None:
    match = DATE.search(value)
    if not match:
        return None
    day, month, year = (int(g) for g in match.groups())
    if year < 100:
        year += 2000 if year < 70 else 1900
    try:
        return dt.date(year, month, day)
    except ValueError:
        return None


# ------------------------------------------------------------------ εισαγωγή ---

def offer_id(relpath: str) -> str:
    """Σταθερό αναγνωριστικό: η διαδρομή του αρχείου μέσα στον φάκελο.

    Τα φύλλα δεν μετακομίζουν — επεξεργάζονται εκεί που είναι, και τα καινούρια
    μπαίνουν στον φάκελο της χρονιάς. Άρα η διαδρομή είναι το σταθερό στοιχείο,
    ενώ το περιεχόμενο (διεύθυνση, ημερομηνία, γραμμές) αλλάζει. Id βασισμένο
    στο περιεχόμενο θα έφτιαχνε νέα προσφορά κάθε φορά που διορθώνεται μια
    διεύθυνση.
    """
    return "hist-" + hashlib.sha1(relpath.encode("utf-8")).hexdigest()[:16]


def content_key(record: dict) -> str:
    """Υπογραφή περιεχομένου, μόνο για την αναφορά.

    Δύο αρχεία με ίδιο περιεχόμενο είναι σχεδόν πάντα αντίγραφο της προηγούμενης
    προσφοράς που δεν συμπληρώθηκε ακόμη. Αναφέρονται ώστε να φανούν, αλλά δεν
    ενώνονται: το αρχείο υπάρχει, άρα η προσφορά υπάρχει.
    """
    parts = [record["address"], str(record["dateEpochDay"])]
    parts += [f'{s["description"]}:{s["area"]}:{s["unitPrice"]}' for s in record["spaces"]]
    parts += record["notes"]
    return "".join(parts)


def convert(path: str, root: str) -> tuple[dict | None, str]:
    relpath = os.path.relpath(path, root)
    rows = read_rows(path)
    if rows is None:
        return None, "δεν διαβάστηκε"
    header = find_header(rows)
    if header is None:
        return None, "χωρίς πίνακα χώρων"

    spaces = parse_spaces(rows, header)
    if not spaces:
        return None, "χωρίς γραμμές χώρων"

    kind, address, when = parse_head(rows, header)
    approximate = when is None
    if when is None:
        year = guess_year(relpath)
        stamp = dt.date.fromtimestamp(os.path.getmtime(path))
        when = stamp if (year is None or stamp.year == year) else dt.date(year, 1, 1)

    notes = parse_block(rows, "ΠΑΡΑΤΗΡΗΣ")
    payment = parse_block(rows, "ΤΡΟΠΟΣ ΠΛΗΡΩΜΗΣ")

    # Η ισχύς είχε δική της γραμμή μέσα στις παρατηρήσεις· τώρα είναι πεδίο
    valid_until = None
    kept = []
    for line in notes:
        match = VALID_UNTIL.search(line)
        if match and valid_until is None:
            valid_until = parse_date_text(match.group(1))
            if valid_until:
                continue
        kept.append(line)

    if not address:
        address = os.path.splitext(os.path.basename(path))[0]

    return {
        "id": offer_id(relpath),
        "address": address,
        "kind": kind,
        "dateEpochDay": (when - EPOCH).days,
        "approximateDate": approximate,
        "status": guess_status(relpath),
        "validUntilDay": (valid_until - EPOCH).days if valid_until else None,
        "paymentTerms": "\n".join(payment),
        "spaces": spaces,
        "notes": kept,
        "source": relpath.replace(os.sep, "/"),
    }, ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--folder", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--report", help="αρχείο με ό,τι παραλείφθηκε και γιατί")
    args = parser.parse_args()

    root = os.path.abspath(args.folder)
    files = []
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if name.lower().endswith((".xls", ".xlsx", ".xlsm")) and not name.startswith("~$"):
                files.append(os.path.join(dirpath, name))
    files.sort()

    offers, spaces, notes, skipped, twins = [], [], [], [], []
    seen_ids = {}
    for path in files:
        record, reason = convert(path, root)
        if record is None:
            skipped.append((os.path.relpath(path, root), reason))
            continue
        # Κάθε αρχείο γίνεται μία προσφορά, ακόμη κι αν το περιεχόμενο είναι
        # πανομοιότυπο με άλλου. Πανομοιότυπα σημαίνει συνήθως αντίγραφο που δεν
        # συμπληρώθηκε ακόμη — χρήσιμη πληροφορία, όχι λόγος να χαθεί το αρχείο.
        key = content_key(record)
        if key in seen_ids:
            twins.append((record["source"], seen_ids[key]))
        else:
            seen_ids[key] = record["source"]

        for i, space in enumerate(record.pop("spaces")):
            spaces.append({
                "id": f"{record['id']}-s{i:03d}",
                "offerId": record["id"],
                "description": space["description"],
                "area": space["area"],
                "unitPrice": space["unitPrice"],
                "position": i,
            })
        for i, line in enumerate(record.pop("notes")):
            notes.append({
                "id": f"{record['id']}-n{i:03d}",
                "offerId": record["id"],
                "text": line,
                "position": i,
            })
        offers.append(record)

    payload = {
        "kind": "prosfora-history",
        "version": 1,
        "generatedAt": dt.datetime.now().isoformat(timespec="seconds"),
        "sourceFolder": os.path.basename(root),
        "offers": offers,
        "spaces": spaces,
        "notes": notes,
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))

    total = sum(
        round(s["area"] * s["unitPrice"], 2) for s in spaces
    )
    years = sorted({dt.date.fromordinal(EPOCH.toordinal() + o["dateEpochDay"]).year for o in offers})
    approx = sum(1 for o in offers if o["approximateDate"])
    done = sum(1 for o in offers if o["status"] == "COMPLETED")

    print(f"αρχεία: {len(files)}")
    print(f"προσφορές: {len(offers)}  (ολοκληρωμένες {done}, ανοιχτές {len(offers) - done})")
    print(f"χώροι: {len(spaces)}   σημειώσεις: {len(notes)}")
    print(f"έτη: {years[0]}–{years[-1]}" if years else "έτη: —")
    print("συνολική αξία: " + f"{total:,.2f}".replace(",", "~").replace(".", ",")
          .replace("~", ".") + " €")
    print(f"ημερομηνία κατά προσέγγιση σε: {approx}")
    print(f"με ίδιο περιεχόμενο με άλλο αρχείο: {len(twins)}")
    print(f"παραλείφθηκαν: {len(skipped)}")
    print(f"γράφτηκε: {args.out} ({os.path.getsize(args.out) / 1e6:.1f} MB)")

    if args.report:
        with open(args.report, "w", encoding="utf-8") as handle:
            for relpath, reason in skipped:
                handle.write(f"{reason}\t{relpath}\n")
            for relpath, original in twins:
                handle.write(f"ίδιο περιεχόμενο με «{original}»\t{relpath}\n")
        print(f"αναφορά: {args.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
