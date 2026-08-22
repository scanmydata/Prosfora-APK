# -*- coding: utf-8 -*-
"""Παράγει το πρότυπο .docx της προσφοράς.

Το πρότυπο είναι *δεδομένα*, όχι κώδικας: ζει στο Drive του χρήστη και το
αλλάζει ελεύθερα. Αυτό εδώ φτιάχνει το εργοστασιακό — αυτό που ανεβαίνει την
πρώτη φορά και αυτό που επιστρέφει η «Επαναφορά εργοστασιακού προτύπου».

Σχεδιαστικοί στόχοι:
  * μία σελίδα A4 όσο το επιτρέπουν οι γραμμές της προσφοράς
  * παρατηρήσεις και τρόπος πληρωμής δίπλα-δίπλα, όχι σε στοίβα
  * το tovapsimo.gr παρόν χωρίς να φωνάζει

Τρέξιμο:  python migration/make_template.py
"""
from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

NAVY = RGBColor(0x1F, 0x38, 0x64)
ACCENT = RGBColor(0xC5, 0x5A, 0x11)
GREY = RGBColor(0x59, 0x59, 0x59)
FONT = "Arial"          # ασφαλές για ελληνικά και στο Word και στο Google Docs

OUT = "assets/pdf-template/ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ.docx"


# --------------------------------------------------------------- helpers ---

def shade(cell, hex_color):
    el = OxmlElement("w:shd")
    el.set(qn("w:val"), "clear")
    el.set(qn("w:fill"), hex_color)
    cell._tc.get_or_add_tcPr().append(el)


def borders(cell, **edges):
    """edges: top/bottom/left/right -> (size_eighths, hex) ή None για κανένα."""
    tcPr = cell._tc.get_or_add_tcPr()
    existing = tcPr.find(qn("w:tcBorders"))
    if existing is not None:
        tcPr.remove(existing)
    node = OxmlElement("w:tcBorders")
    for edge in ("top", "left", "bottom", "right"):
        spec = edges.get(edge, "keep")
        if spec == "keep":
            continue
        e = OxmlElement("w:" + edge)
        if spec is None:
            e.set(qn("w:val"), "none")
            e.set(qn("w:sz"), "0")
        else:
            size, color = spec
            e.set(qn("w:val"), "single")
            e.set(qn("w:sz"), str(size))
            e.set(qn("w:color"), color)
        e.set(qn("w:space"), "0")
        node.append(e)
    tcPr.append(node)


def no_borders(table):
    for row in table.rows:
        for cell in row.cells:
            borders(cell, top=None, left=None, bottom=None, right=None)


def cell_margins(table, top=40, bottom=40, left=80, right=80):
    tblPr = table._tbl.tblPr
    mar = OxmlElement("w:tblCellMar")
    for name, value in (("top", top), ("left", left), ("bottom", bottom), ("right", right)):
        e = OxmlElement("w:" + name)
        e.set(qn("w:w"), str(value))
        e.set(qn("w:type"), "dxa")
        mar.append(e)
    tblPr.append(mar)


def rule(paragraph, hex_color, size=12):
    """Οριζόντια γραμμή ως κάτω περίγραμμα της παραγράφου."""
    pPr = paragraph._p.get_or_add_pPr()
    node = OxmlElement("w:pBdr")
    bottom = OxmlElement("w:bottom")
    bottom.set(qn("w:val"), "single")
    bottom.set(qn("w:sz"), str(size))
    bottom.set(qn("w:space"), "1")
    bottom.set(qn("w:color"), hex_color)
    node.append(bottom)
    pPr.append(node)


def write(paragraph, text, size=9, bold=False, color=None, italic=False, spacing=None):
    run = paragraph.add_run(text)
    run.font.name = FONT
    run.font.size = Pt(size)
    run.bold = bold
    run.italic = italic
    if color is not None:
        run.font.color.rgb = color
    if spacing is not None:                       # αραίωση γραμμάτων, σε twips
        rPr = run._r.get_or_add_rPr()
        el = OxmlElement("w:spacing")
        el.set(qn("w:val"), str(spacing))
        rPr.append(el)
    return run


def para(container, align=WD_ALIGN_PARAGRAPH.LEFT, before=0, after=0, line=1.0):
    p = container.add_paragraph()
    p.alignment = align
    p.paragraph_format.space_before = Pt(before)
    p.paragraph_format.space_after = Pt(after)
    p.paragraph_format.line_spacing = line
    return p


def hanging(paragraph, indent_cm=0.45):
    """Κρεμαστή εσοχή: η δεύτερη γραμμή μιας παρατήρησης δεν πέφτει κάτω από την κουκκίδα."""
    pf = paragraph.paragraph_format
    pf.left_indent = Cm(indent_cm)
    pf.first_line_indent = Cm(-indent_cm)


# ------------------------------------------------------------- το έγγραφο ---

def build():
    doc = Document()

    style = doc.styles["Normal"]
    style.font.name = FONT
    style.font.size = Pt(9)
    style.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
    style.paragraph_format.space_after = Pt(0)
    style.paragraph_format.line_spacing = 1.0

    section = doc.sections[0]
    section.top_margin = Cm(1.1)
    section.bottom_margin = Cm(1.0)
    section.left_margin = Cm(1.6)
    section.right_margin = Cm(1.6)
    width = section.page_width - section.left_margin - section.right_margin

    # --- επικεφαλίδα: ταυτότητα αριστερά, επικοινωνία δεξιά ---------------
    head = doc.add_table(rows=1, cols=2)
    head.alignment = WD_TABLE_ALIGNMENT.CENTER
    head.autofit = False
    no_borders(head)
    cell_margins(head, left=0, right=0)
    for index, w in ((0, Cm(10.5)), (1, Cm(7.3))):
        head.columns[index].width = w
        head.rows[0].cells[index].width = w

    left = head.rows[0].cells[0]
    left.paragraphs[0].text = ""
    write(left.paragraphs[0], "ΓΙΩΡΓΟΣ ΔΟΥΡΑΜΑΝΗΣ", size=13, bold=True, color=NAVY, spacing=20)
    p = para(left)
    write(p, "ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΟΙ · ΑΝΑΚΑΙΝΙΣΕΙΣ · ΜΟΝΩΣΕΙΣ", size=7.5, color=GREY, spacing=24)

    right = head.rows[0].cells[1]
    right.paragraphs[0].text = ""
    right.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    write(right.paragraphs[0], "tovapsimo.gr", size=12, bold=True, color=ACCENT)
    p = para(right, align=WD_ALIGN_PARAGRAPH.RIGHT)
    write(p, "6945 773605 · facebook.com/tovapsimo", size=7.5, color=GREY)

    p = para(doc, before=2, after=8)
    rule(p, "C55A11", size=8)

    # --- τίτλος -----------------------------------------------------------
    p = para(doc, align=WD_ALIGN_PARAGRAPH.CENTER, after=1)
    write(p, "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ", size=15, bold=True, color=NAVY, spacing=40)

    p = para(doc, align=WD_ALIGN_PARAGRAPH.CENTER, after=1)
    write(p, "<<[Είδος]>> ΕΠΙ ΤΗΣ ΟΔΟΥ <<[Οδός / Περιοχή]>>", size=11, bold=True)

    p = para(doc, align=WD_ALIGN_PARAGRAPH.CENTER, after=9)
    write(p, "ΑΘΗΝΑ, <<[Ημερομηνία]>>", size=8.5, color=GREY)

    # --- ανάλυση χώρων ----------------------------------------------------
    cols = (Cm(9.0), Cm(2.6), Cm(2.9), Cm(3.3))
    table = doc.add_table(rows=3, cols=4)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    cell_margins(table, top=50, bottom=50, left=100, right=100)
    for index, w in enumerate(cols):
        table.columns[index].width = w
        for row in table.rows:
            row.cells[index].width = w

    headers = ("ΠΕΡΙΓΡΑΦΗ ΧΩΡΟΥ", "ΕΠΙΦΑΝΕΙΑ (Τ.Μ.)", "ΤΙΜΗ ΜΟΝΑΔΟΣ", "ΣΥΝΟΛΟ")
    for i, text in enumerate(headers):
        cell = table.rows[0].cells[i]
        shade(cell, "1F3864")
        cell.paragraphs[0].text = ""
        cell.paragraphs[0].alignment = (
            WD_ALIGN_PARAGRAPH.LEFT if i == 0 else WD_ALIGN_PARAGRAPH.RIGHT
        )
        write(cell.paragraphs[0], text, size=8, bold=True, color=RGBColor(0xFF, 0xFF, 0xFF))
        borders(cell, top=(6, "1F3864"), bottom=(6, "1F3864"), left=None, right=None)

    # η γραμμή-πρότυπο: επαναλαμβάνεται μία φορά ανά χώρο
    body = (
        "<<Start:[Χώροι]>><<[Περιγραφή Χώρου]>>",
        "<<[Επιφάνεια (τ.μ.)]>>",
        "<<[Τιμή Μονάδος]>>",
        "<<[Σύνολο Γραμμής]>><<End>>",
    )
    for i, text in enumerate(body):
        cell = table.rows[1].cells[i]
        cell.paragraphs[0].text = ""
        cell.paragraphs[0].alignment = (
            WD_ALIGN_PARAGRAPH.LEFT if i == 0 else WD_ALIGN_PARAGRAPH.RIGHT
        )
        write(cell.paragraphs[0], text, size=9)
        borders(cell, top=None, bottom=(4, "D5DCE6"), left=None, right=None)

    total_row = table.rows[2]
    for i in range(4):
        cell = total_row.cells[i]
        shade(cell, "EDF1F7")
        borders(cell, top=(10, "1F3864"), bottom=(10, "1F3864"), left=None, right=None)
        cell.paragraphs[0].text = ""
    total_row.cells[0].paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.LEFT
    write(total_row.cells[0].paragraphs[0], "ΓΕΝΙΚΟ ΣΥΝΟΛΟ", size=10, bold=True, color=NAVY)
    total_row.cells[3].paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    write(total_row.cells[3].paragraphs[0], "<<[Γενικό Σύνολο Live]>>", size=10,
          bold=True, color=NAVY)

    para(doc, after=8)

    # --- παρατηρήσεις + τρόπος πληρωμής, δίπλα-δίπλα ----------------------
    split = doc.add_table(rows=1, cols=2)
    split.alignment = WD_TABLE_ALIGNMENT.CENTER
    split.autofit = False
    no_borders(split)
    cell_margins(split, left=0, right=140)
    for index, w in ((0, Cm(11.4)), (1, Cm(6.4))):
        split.columns[index].width = w
        split.rows[0].cells[index].width = w

    notes = split.rows[0].cells[0]
    notes.paragraphs[0].text = ""
    write(notes.paragraphs[0], "ΠΑΡΑΤΗΡΗΣΕΙΣ", size=9, bold=True, color=NAVY, spacing=20)
    rule(notes.paragraphs[0], "C55A11", size=4)
    p = para(notes, before=3)
    hanging(p)
    write(p, "•  <<[Παρατηρήσεις]>>", size=8.5)

    pay = split.rows[0].cells[1]
    pay.paragraphs[0].text = ""
    write(pay.paragraphs[0], "ΤΡΟΠΟΣ ΠΛΗΡΩΜΗΣ", size=9, bold=True, color=NAVY, spacing=20)
    rule(pay.paragraphs[0], "C55A11", size=4)
    p = para(pay, before=3)
    hanging(p)
    write(p, "•  <<[Τρόπος Πληρωμής]>>", size=8.5)

    p = para(pay, before=7)
    write(p, "ΙΣΧΥΣ ΠΡΟΣΦΟΡΑΣ", size=9, bold=True, color=NAVY, spacing=20)
    rule(p, "C55A11", size=4)
    p = para(pay, before=3)
    write(p, "Η προσφορά ισχύει έως <<[Ισχύει έως]>>", size=8.5)

    para(doc, after=6)

    # --- η έμμεση διαφήμιση ----------------------------------------------
    strip = doc.add_table(rows=1, cols=1)
    strip.alignment = WD_TABLE_ALIGNMENT.CENTER
    strip.autofit = False
    strip.columns[0].width = width
    cell = strip.rows[0].cells[0]
    cell.width = width
    shade(cell, "EDF1F7")
    borders(cell, top=None, bottom=None, left=(30, "C55A11"), right=None)
    cell_margins(strip, top=70, bottom=70, left=160, right=120)
    cell.paragraphs[0].text = ""
    write(cell.paragraphs[0],
          "Φωτογραφίες από ολοκληρωμένα έργα, χρώματα και αξιολογήσεις πελατών: ",
          size=8.5, color=GREY)
    write(cell.paragraphs[0], "tovapsimo.gr", size=9, bold=True, color=ACCENT)

    # --- υπογραφή ---------------------------------------------------------
    p = para(doc, align=WD_ALIGN_PARAGRAPH.RIGHT, before=14)
    write(p, "Ο ΕΡΓΟΛΗΠΤΗΣ", size=8.5, color=GREY, spacing=20)
    p = para(doc, align=WD_ALIGN_PARAGRAPH.RIGHT, before=2)
    write(p, "ΓΙΩΡΓΟΣ ΔΟΥΡΑΜΑΝΗΣ", size=10, bold=True, color=NAVY)
    p = para(doc, align=WD_ALIGN_PARAGRAPH.RIGHT)
    write(p, "6945 773605", size=8.5, color=GREY)

    doc.save(OUT)
    return OUT


if __name__ == "__main__":
    print("γράφτηκε:", build())
