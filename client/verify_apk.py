"""Verify the APK identity, signing certificate and bundled extension bytes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent


def certificate_digests(output):
    return {digest.replace(":", "").lower() for digest in
            re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9:]+)", output)
            if len(digest.replace(":", "")) == 64}


def verify(apk):
    config = json.loads((HERE / "upstream.json").read_text(encoding="utf-8"))
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    versions = sorted((p for p in sdk.iterdir() if (p / "apksigner").is_file()),
                      key=lambda p: [int(n) for n in re.findall(r"\d+", p.name)])
    assert versions, "Android build tools are missing"
    tools = versions[-1]
    signed = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], capture_output=True, text=True, check=True, timeout=60).stdout
    expected_cert = hashlib.sha256(ssl.PEM_cert_to_DER_cert((HERE / "signing-cert.pem").read_text())).hexdigest()
    actual_certs = certificate_digests(signed)
    print("Expected certificate SHA-256:", expected_cert)
    print("Actual certificate SHA-256:", sorted(actual_certs))
    if not actual_certs:
        print("\n".join(line for line in signed.splitlines() if "certificate" in line.lower()))
    assert actual_certs == {expected_cert}, "APK signing certificate mismatch"
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)], capture_output=True, text=True, check=True, timeout=60).stdout
    fields = dict(re.findall(r"(\w+)='([^']*)'", badging.splitlines()[0]))
    assert fields["name"] == config["applicationId"], "Unexpected APK package"
    assert fields["versionName"] == config["versionName"], "Unexpected APK version"
    assert int(fields["versionCode"]) == config["versionCode"], "Unexpected APK version code"
    assert "application-debuggable" not in badging, "Release APK must not be debuggable"
    with zipfile.ZipFile(apk) as archive:
        metadata = json.loads(archive.read("assets/kadrograd/extension.json"))
        payload = archive.read("assets/kadrograd/Kadrograd.cs3")
        assert hashlib.sha256(payload).hexdigest() == metadata["sha256"], "Bundled extension checksum mismatch"
        assert metadata["url"].endswith("Kadrograd-" + metadata["sha256"] + ".cs3")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix(".apk.sha256").write_text(f"{digest}  {apk.name}\n", encoding="ascii")
    print(config["applicationId"], config["versionName"], "signature and bundled extension verified")
    print("APK SHA-256:", digest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify(parser.parse_args().apk)
