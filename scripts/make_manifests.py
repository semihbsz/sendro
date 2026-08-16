#!/usr/bin/env python3
"""Build the two update manifests published with a Sendro release.

docs/UPDATES.md §3 (`latest.json`, the Tauri updater format) and §4
(`android.json`). Both are derived from `release/release.json` — nothing here
is typed twice by a human.

Called by .github/workflows/release.yml after the platform artifacts have been
downloaded, e.g.:

    python3 scripts/make_manifests.py \
        --repo semihbsz/sendro \
        --out dist \
        --windows-setup dist/Sendro_1.2.0_x64-setup.exe \
        --windows-signature dist/Sendro_1.2.0_x64-setup.exe.sig \
        --apk dist/Sendro-1.2.0.apk

Both platforms are optional: with no `--apk` there is simply no
`android.json`, which is what happens before the Android app lands.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASE_JSON = ROOT / "release" / "release.json"

#: The updater target string Tauri sends for a 64-bit Windows build.
WINDOWS_TARGET = "windows-x86_64"


def load_release() -> dict:
    data = json.loads(RELEASE_JSON.read_text(encoding="utf-8"))
    for key in ("version", "pubDate", "notes"):
        if not isinstance(data.get(key), str):
            raise SystemExit(f"error: release/release.json is missing `{key}`.")
    return data


def version_code(version: str) -> int:
    """Same derivation as scripts/bump_version.py — 1.2.0 -> 10200."""
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)$", version)
    if not match:
        raise SystemExit(f"error: {version!r} is not MAJOR.MINOR.PATCH.")
    major, minor, patch = (int(part) for part in match.groups())
    return major * 10_000 + minor * 100 + patch


def asset_url(repo: str, version: str, name: str) -> str:
    return f"https://github.com/{repo}/releases/download/v{version}/{name}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_latest_json(
    release: dict, repo: str, setup: Path, signature: Path
) -> dict:
    """The exact shape `tauri-plugin-updater` expects (UPDATES.md §3).

    `signature` is the *contents* of the bundler's `.sig` file, inline — the
    updater refuses anything it cannot verify against the public key baked
    into tauri.conf.json.

    The extra keys (`notesTr`, `minSupported`, `mandatory`) are ignored by the
    plugin's manifest parser and read back out of `rawJson` by the update
    card, which is how the Turkish notes reach the UI.
    """
    sig = signature.read_text(encoding="utf-8").strip()
    if not sig:
        raise SystemExit(f"error: {signature} is empty — the bundle was not signed.")

    manifest = {
        "version": release["version"],
        "notes": release["notes"],
        "pub_date": release["pubDate"],
        "platforms": {
            WINDOWS_TARGET: {
                "signature": sig,
                "url": asset_url(repo, release["version"], setup.name),
            }
        },
    }
    for key in ("notesTr", "minSupported", "mandatory"):
        if key in release:
            manifest[key] = release[key]
    return manifest


def build_android_json(release: dict, repo: str, apk: Path) -> dict:
    """UPDATES.md §4 — the sideloaded-APK manifest, SHA-256 and all."""
    return {
        "version": release["version"],
        "versionCode": version_code(release["version"]),
        "pubDate": release["pubDate"],
        "notes": release["notes"],
        "notesTr": release.get("notesTr", release["notes"]),
        "minSupported": release.get("minSupported", release["version"]),
        "mandatory": bool(release.get("mandatory", False)),
        "apkUrl": asset_url(repo, release["version"], apk.name),
        "apkSha256": sha256(apk),
        "apkSizeBytes": apk.stat().st_size,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="owner/name, e.g. semihbsz/sendro")
    parser.add_argument("--out", required=True, help="directory to write the manifests to")
    parser.add_argument("--windows-setup", help="path to Sendro_<version>_x64-setup.exe")
    parser.add_argument("--windows-signature", help="path to the matching .sig file")
    parser.add_argument("--apk", help="path to Sendro-<version>.apk")
    args = parser.parse_args()

    release = load_release()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    wrote: list[str] = []

    if args.windows_setup:
        if not args.windows_signature:
            raise SystemExit("error: --windows-setup also needs --windows-signature.")
        setup = Path(args.windows_setup)
        signature = Path(args.windows_signature)
        for path in (setup, signature):
            if not path.exists():
                raise SystemExit(f"error: {path} does not exist.")
        manifest = build_latest_json(release, args.repo, setup, signature)
        (out / "latest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        wrote.append("latest.json")

    if args.apk:
        apk = Path(args.apk)
        if not apk.exists():
            raise SystemExit(f"error: {apk} does not exist.")
        manifest = build_android_json(release, args.repo, apk)
        (out / "android.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        wrote.append("android.json")

    if not wrote:
        raise SystemExit("error: nothing to do — pass --windows-setup and/or --apk.")

    print(f"wrote {', '.join(wrote)} to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
