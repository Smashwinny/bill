#!/usr/bin/env python3
"""Build an offline, separately installable audit source tree; never touches device data."""
import pathlib
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
destination = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "tmp/category-acceptance/isolated-source"
if not destination.is_relative_to(root / "tmp"):
    raise SystemExit("Destination must be inside the project's ignored tmp directory")
if destination.exists():
    raise SystemExit("Use a fresh destination to preserve earlier evidence")
destination.mkdir(parents=True)
files = subprocess.check_output(["git", "--git-dir=.git-repo", "--work-tree=.", "ls-files",
                                "--cached", "--others", "--exclude-standard"],
                                cwd=root, text=True).splitlines()
for name in files:
    if name.startswith(("manual/", "gradle/")) or name in {
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew"
    }:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / name, target)
shutil.copy2(root / "local.properties", destination / "local.properties")

def replace(name, old, new):
    path = destination / name
    content = path.read_text()
    if old not in content:
        raise SystemExit(f"Source changed; review isolation transform: {name}: {old}")
    path.write_text(content.replace(old, new))

replace("settings.gradle.kts", 'include(":app")', '')
replace("manual/build.gradle.kts", 'applicationId = "com.hulk.manualledger"',
        'applicationId = "com.hulk.manualledger.acceptance"')
manifest = "manual/src/main/AndroidManifest.xml"
replace(manifest, "com.hulk.manualledger.permission.READ_LEDGER",
        "com.hulk.manualledger.acceptance.permission.READ_LEDGER")
replace(manifest, "com.hulk.manualledger.sync", "com.hulk.manualledger.acceptance.sync")
replace(manifest, 'android:label="本地账本"', 'android:label="分类验收副本"')
replace(manifest, '    <uses-permission android:name="android.permission.INTERNET" />', '')
for path in (destination / "manual/src/main/java").rglob("*.kt"):
    path.write_text(path.read_text()
                    .replace("com.hulk.manualledger.sync", "com.hulk.manualledger.acceptance.sync")
                    .replace("com.hulk.manualledger.files", "com.hulk.manualledger.acceptance.files"))
shutil.copy2(root / "tools/category-acceptance/ProductCategoryAcceptanceTest.kt",
             destination / "manual/src/androidTest/java/com/hulk/manualledger/ProductCategoryAcceptanceTest.kt")
print(destination)
