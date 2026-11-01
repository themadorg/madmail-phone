"""Validate distribution metadata, embedded engine integrity, ABI and ELF alignment."""

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import zipfile
from pathlib import Path


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--signed", action="store_true")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    tools = sdk / "build-tools/35.0.0"
    badging = run(str(tools / "aapt2"), "dump", "badging", str(args.apk))
    versions = dict(
        line.split("=", 1)
        for line in Path("release.properties").read_text().splitlines()
        if line and not line.startswith("#")
    )
    assert "package: name='org.madmail.mobile'" in badging
    assert f"versionCode='{versions['versionCode']}'" in badging
    assert f"versionName='{versions['versionName']}'" in badging
    assert "application-debuggable" not in badging, "Debuggable APK cannot ship"
    assert args.apk.stat().st_size <= 250 * 1024 * 1024, "Exceeds in-app updater limit"
    with zipfile.ZipFile(args.apk) as apk:
        releases = json.loads(apk.read("assets/engine/releases.json"))
        assert apk.read("assets/engine/LICENCE"), "Missing engine license"
        for release in releases:
            data = apk.read(f"lib/arm64-v8a/{release['file']}")
            assert hashlib.sha256(data).hexdigest() == release["sha256"]
        libraries = [name for name in apk.namelist() if name.startswith("lib/")]
        assert libraries and all(
            name.startswith("lib/arm64-v8a/") for name in libraries
        )
        assert "lib/arm64-v8a/libengine_launcher.so" in libraries
        for name in libraries:
            data = apk.read(name)
            assert data[:6] == b"\x7fELF\x02\x01", f"Not ELF64 little endian: {name}"
            assert struct.unpack_from("<H", data, 18)[0] == 183, f"Not AArch64: {name}"
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            for index in range(count):
                header = offset + index * size
                if struct.unpack_from("<I", data, header)[0] == 1:
                    alignment = struct.unpack_from("<Q", data, header + 48)[0]
                    assert alignment >= 16384, (
                        f"ELF LOAD alignment below 16 KiB: {name}"
                    )
    run(str(tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(args.apk))
    if args.signed:
        expected = re.sub(
            r"[:\s]", "", os.environ.get("EXPECTED_SIGNER_SHA256", "")
        ).lower()
        assert re.fullmatch(r"[0-9a-f]{64}", expected), "Set EXPECTED_SIGNER_SHA256"
        certs = run(
            str(tools / "apksigner"),
            "verify",
            "--verbose",
            "--print-certs",
            str(args.apk),
        )
        assert "CN=Android Debug" not in certs, "Debug certificate cannot ship"
        digests = re.findall(
            r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", certs
        )
        assert digests == [expected], (
            "APK signer differs from expected release identity"
        )
    print(
        "PASS: release metadata, engine hash, ARM64 ELF, 16 KiB alignment"
        + (", and release signer." if args.signed else " (unsigned packaging check).")
    )


if __name__ == "__main__":
    main()
