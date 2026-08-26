# -*- coding: utf-8 -*-
"""Έλεγχος της λογικής του DocxPrintLayout.kt σε πραγματικά αρχεία.

Ίδια βήματα με το Kotlin: A4, περιθώρια σε λογικά όρια, υποσέλιδο με αρίθμηση.
Τρέχει εδώ ώστε να επαληθευτεί ότι το αποτέλεσμα ανοίγει ακόμη ως .docx.

    python migration/check_print_layout.py αρχείο.docx [...]
"""
import io
import re
import sys
import zipfile

MIN_MARGIN, MAX_MARGIN = 454, 1701
A4_WIDTH, A4_HEIGHT = 11906, 16838

DOCUMENT = "word/document.xml"
RELS = "word/_rels/document.xml.rels"
TYPES = "[Content_Types].xml"
FOOTER = "word/footerProsfora.xml"
FOOTER_ID = "rIdProsforaFtr"

WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
FOOTER_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"
FOOTER_TYPE = ("application/vnd.openxmlformats-officedocument."
               "wordprocessingml.footer+xml")


def page_number_footer():
    def run(inner):
        return ('<w:r><w:rPr><w:sz w:val="16"/><w:color w:val="595959"/>'
                f"</w:rPr>{inner}</w:r>")

    def text(value):
        return run(f'<w:t xml:space="preserve">{value}</w:t>')

    def field(instruction):
        return (run('<w:fldChar w:fldCharType="begin"/>')
                + run(f'<w:instrText xml:space="preserve">{instruction}</w:instrText>')
                + run('<w:fldChar w:fldCharType="separate"/>')
                + run("<w:t>1</w:t>")
                + run('<w:fldChar w:fldCharType="end"/>'))

    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<w:ftr xmlns:w="{WORD_NS}">'
            '<w:p><w:pPr><w:jc w:val="right"/></w:pPr>'
            + text("Σελίδα ") + field(" PAGE ") + text(" από ") + field(" NUMPAGES ")
            + "</w:p></w:ftr>")


def normalize(data: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        entries = {n: z.read(n) for n in z.namelist()}

    document = entries[DOCUMENT].decode("utf-8")
    open_at = document.rfind("<w:sectPr")
    close_at = document.find("</w:sectPr>", open_at)
    close = close_at + len("</w:sectPr>")
    section = document[open_at:close]

    a4 = f'<w:pgSz w:w="{A4_WIDTH}" w:h="{A4_HEIGHT}"/>'
    if re.search(r"<w:pgSz[^>]*/>", section):
        section = re.sub(r"<w:pgSz[^>]*/>", a4, section)
    elif "<w:pgMar" in section:
        section = section.replace("<w:pgMar", a4 + "<w:pgMar", 1)
    else:
        section = section.replace("</w:sectPr>", a4 + "</w:sectPr>")

    match = re.search(r"<w:pgMar[^>]*/>", section)
    if match:
        tag = match.group(0)
        for edge in ("top", "right", "bottom", "left"):
            attr = re.search(rf'w:{edge}="(-?[0-9.]+)"', tag)
            if not attr:
                continue
            twips = int(float(attr.group(1)))
            clamped = max(MIN_MARGIN, min(MAX_MARGIN, twips))
            # Γράφεται πάντα ως ακέραιος: κάποια αρχεία έχουν δεκαδικά twips
            tag = tag.replace(attr.group(0), f'w:{edge}="{clamped}"')
        section = section.replace(match.group(0), tag)

    if "w:footerReference" not in section:
        reference = f'<w:footerReference w:type="default" r:id="{FOOTER_ID}"/>'
        section = re.sub(r"<w:sectPr[^>]*>", lambda m: m.group(0) + reference,
                         section, count=1)
        entries[FOOTER] = page_number_footer().encode("utf-8")
        entries[RELS] = entries[RELS].decode("utf-8").replace(
            "</Relationships>",
            f'<Relationship Id="{FOOTER_ID}" Type="{FOOTER_REL}"'
            ' Target="footerProsfora.xml"/></Relationships>',
        ).encode("utf-8")
        entries[TYPES] = entries[TYPES].decode("utf-8").replace(
            "</Types>",
            f'<Override PartName="/word/footerProsfora.xml"'
            f' ContentType="{FOOTER_TYPE}"/></Types>',
        ).encode("utf-8")

    entries[DOCUMENT] = (document[:open_at] + section + document[close:]).encode("utf-8")

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, payload in entries.items():
            z.writestr(name, payload)
    return out.getvalue()


def main():
    for path in sys.argv[1:]:
        raw = open(path, "rb").read()
        before = re.search(r"<w:pgSz[^>]*/>|<w:pgMar[^>]*/>",
                           zipfile.ZipFile(io.BytesIO(raw)).read(DOCUMENT).decode())
        fixed = normalize(raw)

        with zipfile.ZipFile(io.BytesIO(fixed)) as z:
            doc = z.read(DOCUMENT).decode()
            bad = z.testzip()
            has_part = FOOTER in z.namelist()
        print("=" * 78)
        print(path.split("\\")[-1])
        print("  πριν :", before.group(0) if before else "—")
        print("  μετά :", re.search(r"<w:pgSz[^>]*/>", doc).group(0))
        print("        ", re.search(r"<w:pgMar[^>]*/>", doc).group(0))
        added = "προστέθηκε" if has_part else "υπήρχε ήδη"
        print("  υποσέλιδο:", added,
              "| αναφορά:", "ναι" if "w:footerReference" in doc else "ΟΧΙ")
        print("  ακέραιο zip:", "ναι" if bad is None else "ΟΧΙ")

        # Ανοίγει ακόμη ως έγγραφο Word;
        from docx import Document
        reopened = Document(io.BytesIO(fixed))
        print("  ανοίγει με python-docx:", len(reopened.paragraphs), "παράγραφοι")


if __name__ == "__main__":
    main()
