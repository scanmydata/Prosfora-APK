#!/usr/bin/env python3
"""
Δημιουργεί keystore υπογραφής για το Android app χωρίς να χρειάζεται JDK/keytool.

Παράγει PKCS#12 keystore (το Gradle/AGP το δέχεται όπως ένα .jks) με
self-signed πιστοποιητικό 30 ετών, και τυπώνει το SHA-1 fingerprint που
χρειάζεται το Google OAuth client.

    python migration/make_keystore.py --out <φάκελος>

Το αρχείο ΔΕΝ μπαίνει ποτέ στο repo.
"""
from __future__ import annotations

import argparse
import base64
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

ALIAS = "prosfora"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="φάκελος εξόδου")
    parser.add_argument("--cn", default="Giorgos Douramanis")
    parser.add_argument("--org", default="tovapsimo.gr")
    parser.add_argument("--country", default="GR")
    parser.add_argument("--years", type=int, default=30)
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    keystore_path = out_dir / "release.p12"

    if keystore_path.exists():
        print(f"ΥΠΑΡΧΕΙ ΗΔΗ: {keystore_path}")
        print("Δεν το αντικαθιστώ — αν χαθεί το παλιό, δεν μπορείς να κάνεις update το app.")
        return 1

    password = secrets.token_urlsafe(24)

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, args.cn),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, args.org),
        x509.NameAttribute(NameOID.COUNTRY_NAME, args.country),
    ])
    now = datetime.now(timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=365 * args.years))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    blob = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode(),
        key=key,
        cert=cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(password.encode()),
    )
    keystore_path.write_bytes(blob)

    sha1 = cert.fingerprint(hashes.SHA1()).hex().upper()
    sha1_formatted = ":".join(sha1[i:i + 2] for i in range(0, len(sha1), 2))
    sha256 = cert.fingerprint(hashes.SHA256()).hex().upper()
    sha256_formatted = ":".join(sha256[i:i + 2] for i in range(0, len(sha256), 2))

    b64_path = out_dir / "release.p12.b64"
    b64_path.write_text(base64.b64encode(blob).decode(), encoding="ascii")

    pw_path = out_dir / "PASSWORD.txt"
    pw_path.write_text(
        "Keystore για το app Προσφορές\n"
        f"Αρχείο:  {keystore_path.name}\n"
        f"Alias:   {ALIAS}\n"
        f"Κωδικός: {password}\n\n"
        "ΚΡΑΤΑ ΤΟ ΑΝΤΙΓΡΑΦΟ ΑΣΦΑΛΕΙΑΣ. Αν χαθεί αυτό το αρχείο ή ο κωδικός,\n"
        "δεν μπορείς πλέον να δημοσιεύσεις update του ίδιου app — μόνο καινούργιο.\n\n"
        f"SHA-1:   {sha1_formatted}\n"
        f"SHA-256: {sha256_formatted}\n",
        encoding="utf-8",
    )

    print(f"Keystore:  {keystore_path}")
    print(f"Base64:    {b64_path}")
    print(f"Κωδικοί:   {pw_path}")
    print(f"Alias:     {ALIAS}")
    print(f"Κωδικός:   {password}")
    print(f"SHA-1:     {sha1_formatted}")
    print(f"SHA-256:   {sha256_formatted}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
