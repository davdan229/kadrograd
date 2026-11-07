"""Refresh only extension assets in a previously verified APK. Signing follows separately."""
import argparse
import hashlib
from pathlib import Path
import zipfile

ASSETS = ("assets/kadrograd/Kadrograd.cs3", "assets/kadrograd/extension.json")


def refresh(apk: Path, assets: Path, output: Path):
    assert apk.resolve() != output.resolve()
    with zipfile.ZipFile(apk) as previous, zipfile.ZipFile(output, "w") as result:
        assert all(name in previous.namelist() for name in ASSETS)
        for entry in previous.infolist():
            name = entry.filename
            signature = name.startswith("META-INF/") and (
                name.upper().endswith((".RSA", ".DSA", ".EC", ".SF")) or name == "META-INF/MANIFEST.MF"
            )
            if signature:
                continue
            data = (assets / Path(name).name).read_bytes() if name in ASSETS else previous.read(entry)
            result.writestr(entry, data)
    with zipfile.ZipFile(apk) as previous, zipfile.ZipFile(output) as result:
        assert result.testzip() is None
        for name in previous.namelist():
            if name == "AndroidManifest.xml" or name.endswith((".dex", ".so")):
                assert hashlib.sha256(previous.read(name)).digest() == hashlib.sha256(result.read(name)).digest(), name
    print("Refreshed the extension assets. Manifest, app bytecode and native libraries are unchanged.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("assets", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    refresh(args.apk, args.assets, args.output)
