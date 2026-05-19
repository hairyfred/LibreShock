"""Regenerate the LibreShock alarm-stop QR code.

Bundled as a static PNG in both the repo (docs/images/libreshock-qr.png,
for users to print straight from GitHub) and the Android APK
(android/app/src/main/res/drawable/libreshock_qr.png, so the in-app
viewer works offline).

Dev-only dependency: `pip install qrcode[pil]`. Not needed at runtime —
the app + repo ship with the pre-rendered PNG, so re-running this is
only required if the QR_CONTENT string below changes.

Keep QR_CONTENT in sync with the Kotlin constant in
android/app/src/main/java/uk/hairyfred/libreshock/ui/QrCodeContent.kt —
the Android app matches scanned content against that constant, so a
mismatch means the printed QR won't stop the alarm.
"""
import os
import qrcode

QR_CONTENT = "https://www.youtube.com/watch?v=X_8Nh5XfRw0"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = [
    os.path.join(ROOT, "docs", "images", "libreshock-qr.png"),
    os.path.join(
        ROOT, "android", "app", "src", "main", "res", "drawable",
        "libreshock_qr.png",
    ),
]


def main():
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=16,
        border=4,
    )
    qr.add_data(QR_CONTENT)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    for path in TARGETS:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        print(f"wrote {os.path.relpath(path, ROOT)} ({os.path.getsize(path)} bytes)")
    print(f"content: {QR_CONTENT}")


if __name__ == "__main__":
    main()
