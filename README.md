# UniShelf-App
UniShelf Android app releases (APKs) — offline-first university study workspace

## UniShelf V2 Office augmentation

This branch adds a genuine, fully offline LibreOfficeKit editor to the
published UniShelf 1.0.0 arm64-v8a APK without changing the original Flutter
home screen, resources, `classes.dex`, branding or data.

The editor is exposed as **UniShelf Office** through Android's Open/Share
targets and through `unishelf://office?type=writer|calc|impress`. It supports
real DOCX/XLSX/PPTX, ODF, legacy Office formats, text/CSV/RTF and PDF import,
plus editing, Save/Save As, PDF export and printing.

Read `docs/OFFICE.md` for the exact engine version/checksums, supported
formats, privacy model, build instructions and limitations. `docs/VERIFICATION.md`
records the native engine evidence and the on-device verification workflow.

The included build is debug-signed because the original release signing key is
not available. It cannot update the existing release-signed app in place; no
production release is published from this work.
