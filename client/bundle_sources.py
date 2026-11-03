"""Embed the extension built from the same revision into the Android client."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile


def bundle(repository, checkout):
    package = repository / "Kadrograd/build/Kadrograd.cs3"
    payload = package.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    with zipfile.ZipFile(package) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["pluginClassName"] == "ru.kadrograd.KadrogradPlugin"
        assert "classes.dex" in archive.namelist()
    target = checkout / "app/src/main/assets/kadrograd"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(package, target / "Kadrograd.cs3")
    metadata = {
        "internalName": "Kadrograd", "version": manifest["version"], "sha256": digest,
        "url": f"https://raw.githubusercontent.com/davdan229/kadrograd/builds/packages/Kadrograd-{digest}.cs3",
    }
    (target / "extension.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print("Bundled Kadrograd extension", metadata["version"], digest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    bundle(Path(__file__).resolve().parents[1], parser.parse_args().checkout)
