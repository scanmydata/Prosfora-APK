#!/usr/bin/env python3
"""
Reference implementation της απόδοσης του template — η ίδια λογική υλοποιείται
σε Kotlin στο app (gr.prosfora.app.doc.DocxTemplate).

Υπάρχει για να μπορεί να επαληθευτεί το αποτέλεσμα σε πραγματικά δεδομένα χωρίς
συσκευή:

    python migration/render_template.py --offer 795d5415 --out out.docx
    python migration/render_template.py --xls "ΚΟΛΟΚΟΤΡΩΝΗ 36.xls" --out out.docx

Με --dump τυπώνει το κείμενο κάθε παραγράφου του αποτελέσματος, ώστε να
ελέγχεται ότι δεν έμεινε placeholder ασυμπλήρωτο.
"""
from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "assets" / "pdf-template" / "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ.docx"
SEED = ROOT / "app" / "src" / "main" / "assets" / "seed.json"

# Δέχεται και τη γραφή του παλιού προτύπου και τη σύντομη του καινούριου
SPACES_START_RE = re.compile(
    r"&lt;&lt;Start:\s*\[?(?:Related Ανάλυση_Χώρων|Χώροι)\]?&gt;&gt;"
)
NOTES_START_RE = re.compile(r"&lt;&lt;Start:\s*SELECT\(.*?&gt;&gt;", re.S)
LOOP_END = "&lt;&lt;End&gt;&gt;"
NOTE_LINE = "&lt;&lt;[Παρατηρήσεις]&gt;&gt;"
PAYMENT_LINE = "&lt;&lt;[Τρόπος Πληρωμής]&gt;&gt;"


def money(value: float) -> str:
    text = f"{value:,.2f}"
    return text.replace(",", "\x00").replace(".", ",").replace("\x00", ".") + " €"


def number(value: float) -> str:
    text = f"{value:,.2f}"
    return text.replace(",", "\x00").replace(".", ",").replace("\x00", ".")


def enc(value: str) -> str:
    """Ό,τι μπαίνει στο XML πρέπει να είναι escaped — οι διευθύνσεις έχουν & και <."""
    return escape(str(value))


def find_enclosing(xml: str, index: int, tag: str) -> tuple[int, int]:
    """Τα όρια του <tag> ... </tag> που περιέχει τη θέση index."""
    start = max(xml.rfind(f"<{tag} ", 0, index), xml.rfind(f"<{tag}>", 0, index))
    close = xml.index(f"</{tag}>", index) + len(f"</{tag}>")
    return start, close


def find_next(xml: str, index: int, tag: str) -> tuple[int, int]:
    """Τα όρια του επόμενου <tag> μετά τη θέση index.

    Χρειάζεται ξεχωριστά από το find_enclosing: με rfind από μια θέση που είναι
    ήδη μετά το κλείσιμο της προηγούμενης παραγράφου, γυρνάει πίσω σε ΑΥΤΗΝ
    αντί να πάει στην επόμενη.
    """
    match = re.compile(rf"<{tag}[ >]").search(xml, index)
    if match is None:
        raise ValueError(f"δεν βρέθηκε {tag} μετά τη θέση {index}")
    close = xml.index(f"</{tag}>", match.start()) + len(f"</{tag}>")
    return match.start(), close


def repeat_paragraph(xml: str, marker: str, lines: list[str]) -> str:
    """Η παράγραφος με τον marker επαναλαμβάνεται μία φορά ανά γραμμή."""
    at = xml.find(marker)
    if at < 0:
        return xml
    open_, close = find_enclosing(xml, at, "w:p")
    template = xml[open_:close]
    expanded = "".join(template.replace(marker, enc(line)) for line in lines)
    return xml[:open_] + expanded + xml[close:]


def render(xml: str, offer: dict, spaces: list[dict], notes: list[dict]) -> str:
    # --- 1. Επανάληψη γραμμής πίνακα ανά χώρο -------------------------------
    match = SPACES_START_RE.search(xml)
    if match:
        row_start, row_end = find_enclosing(xml, match.start(), "w:tr")
        row_template = xml[row_start:row_end]

        rendered_rows = []
        for space in spaces:
            row = SPACES_START_RE.sub("", row_template).replace(LOOP_END, "")
            row = row.replace("&lt;&lt;[Περιγραφή Χώρου]&gt;&gt;", enc(space["description"]))
            # Το template γράφει την Επιφάνεια χωρίς αγκύλες — δέχομαι και τα δύο
            row = row.replace("&lt;&lt;[Επιφάνεια (τ.μ.)]&gt;&gt;", enc(number(space["area"])))
            row = row.replace("&lt;&lt;Επιφάνεια (τ.μ.)&gt;&gt;", enc(number(space["area"])))
            row = row.replace("&lt;&lt;[Τιμή Μονάδος]&gt;&gt;", enc(money(space["unitPrice"])))
            line_total = round(space["area"] * space["unitPrice"], 2)
            row = row.replace("&lt;&lt;[Σύνολο Γραμμής]&gt;&gt;", enc(money(line_total)))
            rendered_rows.append(row)

        xml = xml[:row_start] + "".join(rendered_rows) + xml[row_end:]

    # --- 2. Μία παράγραφος bullet ανά σημείωση (παλιό πρότυπο) --------------
    match = NOTES_START_RE.search(xml)
    if match:
        start_p_open, start_p_close = find_enclosing(xml, match.start(), "w:p")
        body_p_open, body_p_close = find_next(xml, start_p_close, "w:p")
        body_template = xml[body_p_open:body_p_close]
        end_idx = xml.index(LOOP_END, body_p_close)
        _, end_p_close = find_enclosing(xml, end_idx, "w:p")

        rendered_notes = [
            body_template.replace("&lt;&lt;[Κείμενο]&gt;&gt;", enc(note["text"]))
            for note in notes
        ]
        xml = xml[:start_p_open] + "".join(rendered_notes) + xml[end_p_close:]

    # --- 3. Επαναλαμβανόμενες παράγραφοι (νέο πρότυπο) ----------------------
    xml = repeat_paragraph(xml, NOTE_LINE, [n["text"] for n in notes])
    xml = repeat_paragraph(xml, PAYMENT_LINE, offer.get("paymentLines", []))

    # --- 4. Απλά πεδία ------------------------------------------------------
    total = sum(round(s["area"] * s["unitPrice"], 2) for s in spaces)
    for placeholder, value in {
        "&lt;&lt;[Είδος]&gt;&gt;": offer["kind"],
        "&lt;&lt;[Οδός / Περιοχή]&gt;&gt;": offer["address"],
        "&lt;&lt;[Ημερομηνία]&gt;&gt;": offer["date"],
        "&lt;&lt;[Ισχύει έως]&gt;&gt;": offer.get("validUntil", "—"),
        "&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;": money(total),
        "&lt;&lt;[Γενικό Σύνολο]&gt;&gt;": money(total),
    }.items():
        xml = xml.replace(placeholder, enc(value))

    return xml


def paragraphs(xml: str) -> list[str]:
    """Το κείμενο κάθε παραγράφου — ίδια λογική με το DocxTemplate.extractParagraphs."""
    text_run = re.compile(r"<w:t[^>]*>([^<]*)</w:t>")
    out = []
    for block in re.findall(r"<w:p[ >].*?</w:p>", xml, re.S):
        joined = "".join(text_run.findall(block))
        joined = (joined.replace("&lt;", "<").replace("&gt;", ">")
                  .replace("&quot;", '"').replace("&apos;", "'").replace("&amp;", "&"))
        if joined.strip():
            out.append(joined)
    return out


def from_seed(offer_id: str):
    from datetime import date, timedelta

    data = json.loads(SEED.read_text(encoding="utf-8"))
    offer = next((o for o in data["offers"] if o["id"] == offer_id), None)
    if offer is None:
        print("Δεν βρέθηκε η προσφορά. Διαθέσιμες:")
        for o in data["offers"]:
            print(f"  {o['id']}  {o['address']}")
        raise SystemExit(1)

    offer = dict(offer)
    d = date(1970, 1, 1) + timedelta(days=offer["dateEpochDay"])
    offer["date"] = f"{d.day}/{d.month}/{d.year}"
    v = d + timedelta(days=60)
    offer["validUntil"] = f"{v.day}/{v.month}/{v.year}"
    offer["paymentLines"] = [
        "20% του ποσού με την έναρξη των εργασιών",
        "30% με την πρόοδο των εργασιών",
        "30% με την πρόοδο των εργασιών",
        "20% με την παράδοση του έργου",
    ]
    spaces = [s for s in data["spaces"] if s["offerId"] == offer["id"]]
    notes = [n for n in data["notes"] if n["offerId"] == offer["id"]]
    return offer, spaces, notes


def from_xls(path: str):
    """Διαβάζει ένα από τα υπάρχοντα φύλλα προσφοράς, για έλεγχο σε αληθινό όγκο."""
    import xlrd

    sheet = xlrd.open_workbook(path).sheet_by_index(0)
    rows = [[sheet.cell_value(r, c) for c in range(sheet.ncols)]
            for r in range(sheet.nrows)]

    def cell(r, c):
        v = rows[r][c]
        return v.strip() if isinstance(v, str) else v

    header_row = next(r for r, row in enumerate(rows)
                      if str(row[0]).strip() == "ΠΕΡΙΓΡΑΦΗ ΧΩΡΟΥ")
    spaces = []
    for r in range(header_row + 1, len(rows)):
        name = str(cell(r, 0))
        if not name or name.startswith(("ΣΥΝΟΛΟ", "ΓΕΝΙΚΟ")):
            if name.startswith("ΓΕΝΙΚΟ"):
                break
            if name.startswith("ΣΥΝΟΛΟ"):
                continue
            continue
        area, price = cell(r, 1), cell(r, 2)
        if not isinstance(area, float) or not isinstance(price, float):
            continue
        spaces.append({"description": name, "area": area, "unitPrice": price})

    def block(title):
        start = next((r for r, row in enumerate(rows)
                      if str(row[0]).strip() == title), None)
        if start is None:
            return []
        out = []
        for r in range(start + 1, len(rows)):
            text = str(cell(r, 0))
            if not text:
                break
            out.append(text)
        return out

    notes = [{"text": t} for t in block("ΠΑΡΑΤΗΡΗΣΕΙΣ")]
    offer = {
        "kind": "ΠΟΛΥΚΑΤΟΙΚΙΑΣ",
        "address": str(cell(3, 1)) or str(cell(2, 1)),
        "date": "6/10/2024",
        "validUntil": "30/6/2025",
        "paymentLines": block("ΤΡΟΠΟΣ ΠΛΗΡΩΜΗΣ"),
    }
    return offer, spaces, notes


def main() -> int:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--offer", help="ID προσφοράς από το seed.json")
    source.add_argument("--xls", help="Υπάρχον φύλλο προσφοράς .xls")
    parser.add_argument("--out")
    parser.add_argument("--dump", action="store_true")
    args = parser.parse_args()

    offer, spaces, notes = (from_seed(args.offer) if args.offer else from_xls(args.xls))

    with zipfile.ZipFile(TEMPLATE) as src:
        xml = src.read("word/document.xml").decode("utf-8")
    rendered = render(xml, offer, spaces, notes)

    if args.out:
        out = Path(args.out)
        with zipfile.ZipFile(TEMPLATE) as src, \
                zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
            for item in src.infolist():
                payload = (rendered.encode("utf-8")
                           if item.filename == "word/document.xml"
                           else src.read(item.filename))
                dst.writestr(item, payload)
        print(f"Γράφτηκε: {out}")

    print(f"  {len(spaces)} χώροι, {len(notes)} σημειώσεις, "
          f"{len(offer.get('paymentLines', []))} δόσεις")
    leftovers = [p for p in paragraphs(rendered) if "<<" in p or ">>" in p]
    print("  ασυμπλήρωτα placeholders:", leftovers or "κανένα")
    if args.dump:
        for i, text in enumerate(paragraphs(rendered)):
            print(f"{i:3d}: {text}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
