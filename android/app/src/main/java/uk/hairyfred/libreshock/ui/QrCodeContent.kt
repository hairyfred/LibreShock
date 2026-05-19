package uk.hairyfred.libreshock.ui

/**
 *  The exact string encoded in the bundled QR code (res/drawable/libreshock_qr.png).
 *  Phase D's scan-to-stop flow matches scanned content against this constant —
 *  scanning any other QR (including the vendor app's Pavlok-linked one) won't
 *  unlock a LibreShock-set alarm.
 *
 *  Keep in sync with scripts/generate_qr.py's QR_CONTENT. If you change one,
 *  re-run `python scripts/generate_qr.py` to regenerate both the repo and
 *  bundled-APK PNGs.
 */
const val ALARM_QR_CONTENT = "https://www.youtube.com/watch?v=X_8Nh5XfRw0"
