#!/usr/bin/env python3
"""Patch a decoded UniShelf manifest without rebuilding the resource table.

The original Flutter APK is the product.  This script only registers the
additional OfficeActivity and sets the two application flags required by the
bundled LibreOffice runtime.  It deliberately leaves MainActivity, its filters,
resources, theme and all original metadata in place.
"""

import argparse
import pathlib
import sys

MIME_TYPES = [
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/pdf",
    "application/rtf",
    "text/plain",
    "text/csv",
]

ACTIVITY = """        <activity android:name="org.unishelf.office.OfficeActivity" android:label="UniShelf Office" android:exported="true" android:configChanges="density|fontScale|keyboard|keyboardHidden|layoutDirection|locale|orientation|screenLayout|screenSize|smallestScreenSize|uiMode" android:windowSoftInputMode="adjustResize" android:hardwareAccelerated="true" android:theme="@android:style/Theme.Material.Light.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:host="office" android:scheme="unishelf"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
%s            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND"/>
                <category android:name="android.intent.category.DEFAULT"/>
%s            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE"/>
                <category android:name="android.intent.category.DEFAULT"/>
%s            </intent-filter>
        </activity>
"""


def data_lines(indent="                "):
    return "".join(
        '%s<data android:mimeType="%s"/>\n' % (indent, mime) for mime in MIME_TYPES
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=pathlib.Path)
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    text = args.manifest.read_text(encoding="utf-8")
    if "org.unishelf.office.OfficeActivity" in text:
        print("OfficeActivity already present; manifest unchanged")
        return 0

    insert_before = (
        '        <activity android:configChanges="density|fontScale|keyboard|keyboardHidden|'
        'layoutDirection|locale|orientation|screenLayout|screenSize|smallestScreenSize|uiMode" '
        'android:exported="true" android:hardwareAccelerated="true" android:launchMode="singleTop" '
        'android:name="com.unishelf.app.MainActivity"'
    )
    if insert_before not in text:
        print("MainActivity opening tag not found in decoded manifest", file=sys.stderr)
        return 1

    activity = ACTIVITY % (data_lines(), data_lines(), data_lines())
    text = text.replace(insert_before, activity + insert_before, 1)
    text = text.replace(
        'android:extractNativeLibs="false"',
        'android:extractNativeLibs="true" android:largeHeap="true"',
        1,
    )
    if "android:extractNativeLibs=\"true\"" not in text:
        print("extractNativeLibs flag was not patched", file=sys.stderr)
        return 1
    if args.debug and "android:debuggable=" not in text:
        # apktool --debug also does this, but making it explicit keeps builds
        # deterministic when the script is called directly.
        text = text.replace("<application ", '<application android:debuggable="true" ', 1)
    args.manifest.write_text(text, encoding="utf-8")
    print("patched", args.manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
