# Verification record

This file records only evidence that was actually produced. It does not claim
that an Android runtime test has passed before the workflow has run.

## 1. Source and release facts

* Repository base before this work: `dc914df8a50e66cab839096e99aaa44b5a413723`
  (the latest remote commit at the start of the V2 execution).
* The repository contains no Flutter source tree, no `pubspec.yaml`, no
  `android/` project and no release signing key. `/root/UniShelf-source` is the
  public Next.js marketing site, not the Flutter application.
* The published base artifact is
  `UniShelf-arm64-v8a.apk`, 42,395,877 bytes, SHA-256
  `f67f918ad2e7f615f8a344dfd84f3aff7e05187ad763963258f3998084613902`.
* The base APK's original `classes.dex` is preserved exactly:
  SHA-256 `fc2da1a32f8bbb4f730ae4314fefe70d297802667cf4a74182a582019572e1aa`.
* Package identity remains `com.unishelf.app`, versionName `1.0.0`,
  versionCode `2001`, minSdk 24, targetSdk 36.

## 2. Genuine native engine evidence

Engine: LibreOffice Viewer for Android 26.2.7.3, F-Droid versionCode 135,
SHA-256 `e921bfb14170824572eb1e33643538803681226427948c245084f5e6356c3775`.

A native bionic harness loaded the real `liblo-native-code.so`, initialized
`libreofficekit_initialize`, obtained a hook through `libreofficekit_hook_2`,
loaded genuine documents, painted tiles, posted UNO edits, and exported files.
Representative log lines:

```text
JNI_OnLoad -> 65542
initialize returned 1
hook2 programDir=/tmp/opencode/lo/data2/program profile=file:///tmp/opencode/lok-profile2
loading /tmp/opencode/poi-test/out/smoke.docx
document=0xb400007d144ecd20 type=0
paintTile nonzero bytes=6345
inserting text via UNO
saveAs docx ok=1
saveAs pdf ok=1
```

Verified native conversions (all output files were nonempty and were re-opened
by the engine):

| Input | Output | Output bytes |
|---|---:|---:|
| smoke.docx | writer-docx.docx | 5,091 |
| smoke.docx | writer-docx.pdf | 25,643 |
| smoke.docx | writer-odt.odt | 9,451 |
| smoke.docx | writer-odt.pdf | 25,643 |
| smoke.xlsx | calc-xlsx.xlsx | 5,062 |
| smoke.xlsx | calc-xlsx.pdf | 10,230 |
| smoke.xlsx | calc-ods.ods | 8,055 |
| smoke.xlsx | calc-ods.pdf | 10,232 |
| blank.pptx | impress-pptx.pptx | 27,604 |
| blank.pptx | impress-pptx.pdf | 6,412 |
| blank.pptx | impress-odp.odp | 19,022 |
| blank.pptx | impress-odp.pdf | 6,414 |
| writer-pdf.pdf | pdf-roundtrip.pdf | 19,568 |

Recorded output hashes include:

```text
27b355ebe98c871f0925b2656158c6673fa9b1847ea99c4128ea325c8431441c  writer-docx.docx
3392b02a841d468ceca1a25a91489230693951dff6ace147959d1d6adae07ba4  writer-docx.pdf
ffbc0623d431f3b57f03301c7d1521898134e4e7b45ab7a399a2ea408b4b75b1  calc-xlsx.xlsx
7a37cbe5271fc0af069ec9d664a97446e41271bbe1306d9ab76a88b4710463f3  impress-pptx.pptx
```

PDF import is supported by LibreOffice's Draw/PDF filter. A 512×512 top-left
tile of the test PDF was entirely white (`paintTile nonzero bytes=0`), which is
consistent with the page margin at that coordinate; the PDF was still loaded
and re-exported successfully. Full-page PDF tile rendering is exercised by the
on-device self-test when it becomes available; it is not claimed as verified by
the native top-left tile alone.

## 3. APK structural evidence

The reproducible script builds a debug-signed self-test APK from the public
base release and the official engine package. Structural checks performed
locally:

* Original `classes.dex` byte-identical to the base APK.
* New `classes2.dex` present (98,908 bytes in the current build).
* Engine `liblo-native-code.so` and all NSS/NSPR/SQLite/libc++ dependencies
  present under `lib/arm64-v8a/`.
* `assets/program`, `assets/share`, `assets/unpack` present.
* Blank DOCX/XLSX/PPTX/ODT/ODS/ODP templates and `ENGINE.txt` present.
* Original Flutter assets remain in the APK.
* Manifest still has `com.unishelf.app.MainActivity`; the new activity is
  `org.unishelf.office.OfficeActivity`; `extractNativeLibs=true` and
  `largeHeap=true` are set.
* APK zip integrity and APK signature verification pass.

A previous local self-test APK had SHA-256
`982eec7964045ce64064e299e89518c2854f57c168436d10e075a6b6f1535980`.
That APK was built before the latest self-test-reopen and page-fit changes; the
CI run rebuilds the current source and publishes the current checksum as an
artifact.

## 4. On-device test

The GitHub Actions workflow `.github/workflows/office-apk.yml` builds the
self-test APK and boots an arm64 Android 30 emulator. The self-test:

1. copies `assets/unishelf_office/selftest.docx` into the app cache;
2. loads it through the actual `OfficeEngine` and LibreOfficeKit;
3. renders a 512×512 engine tile and counts non-white pixels;
4. inserts text through LOK's UNO bridge;
5. saves a real DOCX and a real PDF;
6. reopens the saved DOCX through the engine;
7. writes `selftest-result.txt` with byte sizes and `result=PASS` only when all
   stages succeed.

The workflow uploads the result and logcat as diagnostics. This is the
authoritative runtime check; a local emulator is unavailable on this aarch64
Termux/proot host.

## 5. What is not verified

* Installation as an in-place update over the release-signed app: impossible
  without the original private signing key.
* The original Flutter UI's runtime behaviour and the features whose source is
  absent (scanner, GPA, library, planner, notes, Appwrite/backup) cannot be
  modified or rebuilt here. They remain in the original APK binary exactly as
  released.
* The complete LibreOffice Viewer UI was not integrated; the added UI is a
  small platform editor exposing the real engine.
* Exhaustive PDF editing, macros, external media and every advanced Office
  feature are not claimed.
