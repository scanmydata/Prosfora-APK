# -*- coding: utf-8 -*-
"""Ετοιμάζει το «κλασικό» πρότυπο — αυτό με τα λογότυπα και τις φωτογραφίες.

Πηγή είναι το πρότυπο που ερχόταν από το AppSheet. Δεν ξαναφτιάχνεται από την
αρχή: κρατιέται όπως είναι (εικόνες, δείγματα εργασιών, στοιχεία επικοινωνίας)
και του προστίθενται μόνο όσα λείπουν —

  * αρίθμηση σελίδων κάτω δεξιά, σε κάθε σελίδα
  * αέρας στην κορυφή από τη 2η σελίδα και μετά
  * ισχύς προσφοράς και τρόπος πληρωμής

Τρέξιμο:
    python migration/make_classic_template.py --source "…/ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ.docx"
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

from make_template import FONT, GREY, NAVY, field, para, rule, write

OUT = "assets/pdf-template/ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ — κλασικό.docx"

# Πόσος αέρας στην κορυφή των σελίδων μετά την πρώτη, σε στιγμές
TOP_AIR_PT = 34


def furnish(section):
    """Αρίθμηση σελίδων και αέρας στις επόμενες σελίδες.

    Ίδια λύση με το άλλο πρότυπο: τα περιθώρια δεν αλλάζουν ανά σελίδα, οπότε ο
    χώρος στην κορυφή βγαίνει από κεφαλίδα που υπάρχει μόνο στις σελίδες μετά
    την πρώτη.
    """
    section.different_first_page_header_footer = True
    section.header_distance = Cm(0.6)
    section.footer_distance = Cm(0.6)

    first = section.first_page_header.paragraphs[0]
    first.paragraph_format.space_after = Pt(0)
    write(first, " ", size=1)

    rest = section.header.paragraphs[0]
    rest.paragraph_format.space_after = Pt(TOP_AIR_PT)
    write(rest, " ", size=1)

    for footer in (section.first_page_footer, section.footer):
        p = footer.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        p.paragraph_format.space_before = Pt(0)
        write(p, "Σελίδα ", size=8, color=GREY)
        field(p, " PAGE ", "1", size=8, color="595959")
        write(p, " από ", size=8, color=GREY)
        field(p, " NUMPAGES ", "1", size=8, color="595959")


def find_paragraph(doc, needle):
    for p in doc.paragraphs:
        if needle in p.text:
            return p
    return None


def insert_before(anchor, doc, builder):
    """Φτιάχνει παράγραφο στο τέλος και τη μετακινεί πριν το [anchor]."""
    p = builder(doc)
    anchor._p.addprevious(p._p)
    return p


def add_terms(doc):
    """Ισχύς και τρόπος πληρωμής, πριν από τα δείγματα εργασιών."""
    anchor = find_paragraph(doc, "ΔΕΙΓΜΑΤΑ ΕΡΓΑΣΙΩΝ")
    if anchor is None:
        raise SystemExit("δεν βρέθηκε το σημείο «ΔΕΙΓΜΑΤΑ ΕΡΓΑΣΙΩΝ»")

    def heading(text):
        def build(d):
            p = para(d, before=8, after=2)
            write(p, text, size=10, bold=True, color=NAVY, spacing=20)
            rule(p, "C55A11", size=4)
            return p
        return build

    def line(text, indent=0.45):
        def build(d):
            p = para(d, after=1)
            if indent:
                p.paragraph_format.left_indent = Cm(indent)
                p.paragraph_format.first_line_indent = Cm(-indent)
            write(p, text, size=10)
            return p
        return build

    insert_before(anchor, doc, heading("ΤΡΟΠΟΣ ΠΛΗΡΩΜΗΣ"))
    insert_before(anchor, doc, line("•  <<[Τρόπος Πληρωμής]>>"))
    insert_before(anchor, doc, heading("ΙΣΧΥΣ ΠΡΟΣΦΟΡΑΣ"))
    insert_before(anchor, doc, line("Η προσφορά ισχύει έως <<[Ισχύει έως]>>", indent=0))
    insert_before(anchor, doc, lambda d: para(d, after=6))


def build(source):
    doc = Document(source)

    section = doc.sections[0]
    # Το πρωτότυπο είναι ήδη A4, αλλά ας μην εξαρτιόμαστε από αυτό
    section.page_width = Cm(21.0)
    section.page_height = Cm(29.7)
    # Πάνω/κάτω ήταν μισό εκατοστό — πολύ κολλητά για εκτύπωση
    section.top_margin = Cm(1.2)
    section.bottom_margin = Cm(1.1)
    section.left_margin = Cm(1.8)
    section.right_margin = Cm(1.8)

    furnish(section)
    add_terms(doc)

    doc.save(OUT)
    return OUT


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    args = parser.parse_args()
    print("γράφτηκε:", build(args.source))
