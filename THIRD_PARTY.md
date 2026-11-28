# Third-party notices

UniShelf Office uses the official LibreOffice Viewer for Android engine and
the upstream LibreOfficeKit Java bridge. The base UniShelf application is the
published `com.unishelf.app` release and is not relicensed or modified in its
original `classes.dex`, resources, assets or Flutter libraries.

## LibreOffice / LibreOfficeKit

* Project: LibreOffice
* Source: https://github.com/LibreOffice/core
* Engine package: LibreOffice Viewer 26.2.7.3, F-Droid versionCode 135
* Package source: https://f-droid.org/packages/org.documentfoundation.libreoffice/
* License: Mozilla Public License 2.0 (MPL-2.0), with additional compatible
  upstream licenses in the source tree. MPL text:
  `LICENSES/MPL-2.0.txt`.

The Java bridge files under `office/src/org/libreoffice/kit/` retain their
upstream MPL-2.0 headers. The native engine is distributed unmodified inside
the APK. Its source is obtainable from the LibreOffice project and the F-Droid
package metadata.

## NSS / NSPR

The engine's TLS/crypto modules use NSS and NSPR, distributed under MPL-2.0.
The libraries are packaged unmodified from the official LibreOffice APK.

## SQLite

SQLite is in the public domain. The engine's `libsqlite3.so` is used because
the upstream NSS module requires the versioned `SQLITE_3` ABI.

## LLVM libc++

`libc++_shared.so` is distributed under the University of Illinois/NCSA
Open Source License / Apache License 2.0 with LLVM exceptions. It is packaged
unmodified from the official LibreOffice APK.

## Original UniShelf application

The original UniShelf application, its logo, name, Flutter assets and release
artifacts remain subject to their existing ownership and licenses. No release
signing key or secret is included in this repository.
