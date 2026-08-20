#!/usr/bin/env python3
"""
Reference implementation της απόδοσης του template — η ίδια λογική υλοποιείται
σε Kotlin στο app (gr.prosfora.app.doc.DocxTemplate).

Υπάρχει για να μπορεί να επαληθευτεί το αποτέλεσμα σε πραγματικά δεδομένα χωρίς
συσκευή:

    python migration/render_template.py --offer 795d5415 --out /tmp/out.docx
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "assets" / "pdf-template" / "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ.docx"
SEED = ROOT / "app" / "src" / "main" / "assets" / "seed.json"

SPACES_START = "&lt;&lt;Start:[Related Ανάλυση_Χώρων]&gt;&gt;"
NOTES_START_RE = re.compile(r"&lt;&lt;Start:\s*SELECT\(.*?&gt;&gt;", re.S)
LOOP_END = "&lt;&lt;End&gt;&gt;"


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


def render(xml: str, offer: dict, spaces: list[dict], notes: list[dict]) -> str:
    # --- 1. Επανάληψη γραμμής πίνακα ανά χώρο -------------------------------
    idx = xml.index(SPACES_START)
    row_start, row_end = find_enclosing(xml, idx, "w:tr")
    row_template = xml[row_start:row_end]

    rendered_rows = []
    for space in spaces:
        row = row_template
        row = row.replace(SPACES_START, "").replace(LOOP_END, "")
        row = row.replace("&lt;&lt;[Περιγραφή Χώρου]&gt;&gt;", enc(space["description"]))
        # Το template γράφει την Επιφάνεια χωρίς αγκύλες — δέχομαι και τα δύο
        row = row.replace("&lt;&lt;[Επιφάνεια (τ.μ.)]&gt;&gt;", enc(number(space["area"])))
        row = row.replace("&lt;&lt;Επιφάνεια (τ.μ.)&gt;&gt;", enc(number(space["area"])))
        row = row.replace("&lt;&lt;[Τιμή Μονάδος]&gt;&gt;", enc(money(space["unitPrice"])))
        line_total = round(space["area"] * space["unitPrice"], 2)
        row = row.replace("&lt;&lt;[Σύνολο Γραμμής]&gt;&gt;", enc(money(line_total)))
        rendered_rows.append(row)

    xml = xml[:row_start] + "".join(rendered_rows) + xml[row_end:]

    # --- 2. Μία παράγραφος bullet ανά σημείωση ------------------------------
    match = NOTES_START_RE.search(xml)
    if match:
        start_p_open, start_p_close = find_enclosing(xml, match.start(), "w:p")
        body_p_open, body_p_close = find_next(xml, start_p_close, "w:p")
        body_template = xml[body_p_open:body_p_close]
        end_idx = xml.index(LOOP_END, body_p_close)
        end_p_open, end_p_close = find_enclosing(xml, end_idx, "w:p")

        rendered_notes = [
            body_template.replace("&lt;&lt;[Κείμενο]&gt;&gt;", enc(note["text"]))
            for note in notes
        ]
        xml = xml[:start_p_open] + "".join(rendered_notes) + xml[end_p_close:]

    # --- 3. Απλά πεδία ------------------------------------------------------
    total = sum(round(s["area"] * s["unitPrice"], 2) for s in spaces)
    for placeholder, value in {
        "&lt;&lt;[Είδος]&gt;&gt;": offer["kind"],
        "&lt;&lt;[Οδός / Περιοχή]&gt;&gt;": offer["address"],
        "&lt;&lt;[Ημερομηνία]&gt;&gt;": offer["date"],
        "&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;": money(total),
        "&lt;&lt;[Γενικό Σύνολο]&gt;&gt;": money(total),
    }.items():
        xml = xml.replace(placeholder, enc(value))

    return xml


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offer", required=True, help="ID προσφοράς από το seed.json")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    data = json.loads(SEED.read_text(encoding="utf-8"))
    offer = next((o for o in data["offers"] if o["id"] == args.offer), None)
    if offer is None:
        print("Δεν βρέθηκε η προσφορά. Διαθέσιμες:")
        for o in data["offers"]:
            print(f"  {o['id']}  {o['address']}")
        return 1

    from datetime import date, timedelta
    offer = dict(offer)
    d = date(1970, 1, 1) + timedelta(days=offer["dateEpochDay"])
    offer["date"] = f"{d.day}/{d.month}/{d.year}"

    spaces = [s for s in data["spaces"] if s["offerId"] == offer["id"]]
    notes = [n for n in data["notes"] if n["offerId"] == offer["id"]]

    out = Path(args.out)
    shutil.copy(TEMPLATE, out)
    source = zipfile.ZipFile(TEMPLATE)
    xml = source.read("word/document.xml").decode("utf-8")
    rendered = render(xml, offer, spaces, notes)

    with zipfile.ZipFile(TEMPLATE) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            payload = rendered.encode("utf-8") if item.filename == "word/document.xml" else src.read(item.filename)
            dst.writestr(item, payload)

    print(f"Γράφτηκε: {out}")
    print(f"  {len(spaces)} χώροι, {len(notes)} σημειώσεις")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
