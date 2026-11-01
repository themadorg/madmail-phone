"""Losslessly recompress an unsigned APK before zipalign and signing."""

import argparse
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.destination.resolve():
        parser.error("Use a separate output file")
    with zipfile.ZipFile(args.source) as source:
        entries = source.infolist()
        if any(
            item.filename.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))
            for item in entries
        ):
            parser.error("Input must be unsigned; compress before signing")
        with zipfile.ZipFile(args.destination, "w") as destination:
            for item in entries:
                destination.writestr(
                    item,
                    source.read(item),
                    compress_type=item.compress_type,
                    compresslevel=9,
                )
    with (
        zipfile.ZipFile(args.source) as source,
        zipfile.ZipFile(args.destination) as destination,
    ):
        assert source.namelist() == destination.namelist()
        for name in source.namelist():
            assert source.read(name) == destination.read(name), (
                f"Content changed: {name}"
            )
    print(
        f"Lossless APK compression: {args.source.stat().st_size:,} → {args.destination.stat().st_size:,} bytes"
    )


if __name__ == "__main__":
    main()
