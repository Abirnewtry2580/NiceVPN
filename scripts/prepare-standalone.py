#!/usr/bin/env python3
"""Overlay NiceVPN's UI on a pinned OpenVPN for Android source checkout."""
from __future__ import annotations

import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
import re

ANDROID = "{http://schemas.android.com/apk/res/android}"
ENGINE_COMMIT = "bd8677a8056290aa444d36f64df037c34a2c2ec6"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} marker, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: prepare-standalone.py /path/to/ics-openvpn-checkout")

    repo = Path(__file__).resolve().parents[1]
    engine = Path(sys.argv[1]).resolve()
    if not (engine / ".git").exists():
        raise SystemExit("OpenVPN source checkout is missing")
    actual = (engine / ".git").read_text().strip() if (engine / ".git").is_file() else None
    if actual and "gitdir:" not in actual:
        raise SystemExit("Unexpected git worktree metadata")
    revision = __import__("subprocess").check_output(
        ["git", "-C", str(engine), "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != ENGINE_COMMIT:
        raise SystemExit(f"Unexpected OpenVPN source revision: {revision}")

    # The upstream API, VPN service and engine stay together in one APK.
    app_build = engine / "main/build.gradle.kts"
    build_text = app_build.read_text()
    build_text = replace_once(
        build_text,
        "        minSdk = 23\n",
        "        minSdk = 26\n        applicationId = \"com.nicevpn\"\n",
        "engine application ID",
    )
    app_build.write_text(build_text)

    # This narrowly permits the bundled app to call its own AIDL service.
    # Calls from other package UIDs still need the upstream allow-list approval.
    external_db = engine / "main/src/main/java/de/blinkt/openvpn/api/ExternalAppDatabase.java"
    db_text = external_db.read_text()
    db_text = replace_once(
        db_text,
        "    public String checkOpenVPNPermission(PackageManager pm) throws SecurityRemoteException {\n",
        "    public String checkOpenVPNPermission(PackageManager pm) throws SecurityRemoteException {\n"
        "        if (Binder.getCallingUid() == android.os.Process.myUid()) {\n"
        "            return mContext.getPackageName();\n"
        "        }\n\n",
        "self API authorization",
    )
    external_db.write_text(db_text)

    # Overlay the existing provider UI and parser code, excluding the separate-app AIDL copies.
    java_src = repo / "app/src/main/java/com/nicevpn"
    java_dst = engine / "main/src/main/java/com/nicevpn"
    if java_dst.exists():
        shutil.rmtree(java_dst)
    shutil.copytree(java_src, java_dst)

    # Use the app's existing parser tests in the upstream Android test module.
    test_src = repo / "app/src/test/java/com/nicevpn"
    test_dst = engine / "main/src/test/java/com/nicevpn"
    if test_dst.exists():
        shutil.rmtree(test_dst)
    shutil.copytree(test_src, test_dst)

    # Give the combined APK the NiceVPN launcher artwork without colliding with upstream resources.
    res = engine / "main/src/main/res"
    icon_src = repo / "app/src/main/res/drawable/ic_launcher_foreground.xml"
    icon_dst = res / "drawable/nicevpn_launcher_foreground.xml"
    icon_dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(icon_src, icon_dst)
    adaptive_dir = res / "mipmap-anydpi-v26"
    adaptive_dir.mkdir(parents=True, exist_ok=True)
    (adaptive_dir / "nicevpn_launcher.xml").write_text(
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\\n'
        '    <background android:drawable="@color/nicevpn_launcher_background" />\\n'
        '    <foreground android:drawable="@drawable/nicevpn_launcher_foreground" />\\n'
        '</adaptive-icon>\\n'
    )
    values = res / "values"
    values.mkdir(parents=True, exist_ok=True)
    (values / "nicevpn_resources.xml").write_text(
        '<resources>\\n'
        '    <color name="nicevpn_launcher_background">#102B3D</color>\\n'
        '    <string name="nicevpn_app_name">NiceVPN</string>\\n'
        '</resources>\\n'
    )

    # Route service binding to this APK and avoid the old companion-app install prompt.
    activity_file = java_dst / "MainActivity.java"
    activity_text = activity_file.read_text()
    activity_text = replace_once(
        activity_text,
        "        serviceIntent.setPackage(OPENVPN_PACKAGE);",
        "        serviceIntent.setPackage(getPackageName());",
        "bundled service package",
    )
    activity_text, count = re.subn(
        r"    private void showOpenVpnMissing\\(\\) \\{.*?"
        r"    private void requestOpenVpnApiPermission\\(\\)",
        "    private void showOpenVpnMissing() {\\n"
        "        String message = \"NiceVPN's built-in VPN engine could not be reached. Retry the connection.\";\\n"
        "        status.setText(message);\\n"
        "        Toast.makeText(this, message, Toast.LENGTH_LONG).show();\\n"
        "    }\\n\\n"
        "    private void requestOpenVpnApiPermission()",
        activity_text,
        count=1,
        flags=re.S,
    )
    if count != 1:
        raise SystemExit("Could not replace the external-app install prompt")
    activity_file.write_text(activity_text)

    # Point the launcher at NiceVPN while retaining the OpenVPN service components.
    manifest = engine / "main/src/main/AndroidManifest.xml"
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    root = ET.parse(manifest)
    application = root.find("application")
    if application is None:
        raise SystemExit("OpenVPN application element not found")

    for component in list(application):
        if component.tag not in ("activity", "activity-alias"):
            continue
        for intent_filter in list(component.findall("intent-filter")):
            actions = {node.get(ANDROID + "name") for node in intent_filter.findall("action")}
            categories = {node.get(ANDROID + "name") for node in intent_filter.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                component.remove(intent_filter)

    application.set(ANDROID + "icon", "@mipmap/nicevpn_launcher")
    application.set(ANDROID + "roundIcon", "@mipmap/nicevpn_launcher")
    application.set(ANDROID + "label", "@string/nicevpn_app_name")
    activity = ET.SubElement(application, "activity", {
        ANDROID + "name": "com.nicevpn.MainActivity",
        ANDROID + "exported": "true",
        ANDROID + "label": "@string/nicevpn_app_name",
        ANDROID + "theme": "@style/blinkt",
    })
    intent_filter = ET.SubElement(activity, "intent-filter")
    ET.SubElement(intent_filter, "action", {ANDROID + "name": "android.intent.action.MAIN"})
    ET.SubElement(intent_filter, "category", {ANDROID + "name": "android.intent.category.LAUNCHER"})
    root.write(manifest, encoding="utf-8", xml_declaration=True)

    # Keep the upstream license beside the generated source workspace for the APK build.
    shutil.copyfile(engine / "doc/LICENSE.txt", repo / "build-openvpn-license.txt")
    print(f"Prepared NiceVPN with bundled engine from {revision}")


if __name__ == "__main__":
    main()
