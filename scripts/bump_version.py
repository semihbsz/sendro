#!/usr/bin/env python3
"""Sendro version sync — docs/UPDATES.md §1 and §5.

`release/release.json` is the single source of truth for the version number.
Every platform manifest has to agree with it, or the release workflow refuses
to run:

  release/release.json  ->  desktop/package.json           "version"
                            desktop/src-tauri/tauri.conf.json  "version"
                            desktop/src-tauri/Cargo.toml   [package] version
                            core/Cargo.toml                [package] version
                            ios/project.yml                MARKETING_VERSION
                            android/app/build.gradle.kts   versionName + versionCode

Usage:

    python3 scripts/bump_version.py            # write the version everywhere
    python3 scripts/bump_version.py --check     # CI: exit 1 if anything drifts
    python3 scripts/bump_version.py --version 1.3.0
                                                # also rewrites release.json

The Android app is being written in a parallel branch, so
`android/app/build.gradle.kts` is treated as optional: missing is fine and
reported, present means it is kept in sync like everything else.

`versionCode` is derived, never hand-edited:  major*10000 + minor*100 + patch
(1.2.0 -> 10200), which is monotonic for any sane semver stream.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASE_JSON = ROOT / "release" / "release.json"

SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


# ---------------------------------------------------------------------------
# Version helpers
# ---------------------------------------------------------------------------


def parse_semver(version: str) -> tuple[int, int, int]:
    match = SEMVER.match(version.strip())
    if not match:
        raise SystemExit(
            f"error: {version!r} is not a plain MAJOR.MINOR.PATCH version. "
            "The Android versionCode and the Tauri updater both need one."
        )
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def version_code(version: str) -> int:
    """Android `versionCode`: 1.2.0 -> 10200. Monotonic with semver."""
    major, minor, patch = parse_semver(version)
    if minor > 99 or patch > 99:
        raise SystemExit(
            f"error: {version} cannot be encoded as a versionCode "
            "(minor and patch must stay below 100)."
        )
    return major * 10_000 + minor * 100 + patch


def read_release_version() -> str:
    if not RELEASE_JSON.exists():
        raise SystemExit(f"error: {RELEASE_JSON} is missing — see docs/UPDATES.md §1.")
    data = json.loads(RELEASE_JSON.read_text(encoding="utf-8"))
    version = data.get("version")
    if not isinstance(version, str):
        raise SystemExit(f"error: {RELEASE_JSON} has no string `version` field.")
    parse_semver(version)
    return version


def write_release_version(version: str) -> bool:
    """Rewrite release.json's version in place, preserving the file's layout."""
    text = RELEASE_JSON.read_text(encoding="utf-8")
    updated, count = re.subn(
        r'("version"\s*:\s*")[^"]*(")',
        lambda m: f"{m.group(1)}{version}{m.group(2)}",
        text,
        count=1,
    )
    if count != 1:
        raise SystemExit(f"error: could not find `version` in {RELEASE_JSON}.")
    if updated == text:
        return False
    RELEASE_JSON.write_text(updated, encoding="utf-8")
    return True


# ---------------------------------------------------------------------------
# Targets
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Rule:
    """One `version = X` site inside one file.

    `pattern` must capture three groups: the prefix, the current value, and
    the suffix — so the file's own quoting and indentation survive a rewrite.
    """

    label: str
    pattern: re.Pattern[str]
    #: Builds the wanted value (group 2) from the release version.
    value: Callable[[str], str]


@dataclass(frozen=True)
class Target:
    path: Path
    rules: tuple[Rule, ...]
    #: Android is being written in parallel — its file may not exist yet.
    optional: bool = False


def literal(version: str) -> str:
    return version


def targets() -> list[Target]:
    return [
        Target(
            ROOT / "desktop" / "package.json",
            (
                Rule(
                    "version",
                    # Anchored to the two-space top-level key so a nested
                    # "version" in some future block can never be hit.
                    re.compile(r'(?m)^(  "version": ")([^"]*)(")'),
                    literal,
                ),
            ),
        ),
        Target(
            ROOT / "desktop" / "src-tauri" / "tauri.conf.json",
            (
                Rule(
                    "version",
                    re.compile(r'(?m)^(  "version": ")([^"]*)(")'),
                    literal,
                ),
            ),
        ),
        Target(
            ROOT / "desktop" / "src-tauri" / "Cargo.toml",
            (
                Rule(
                    "[package] version",
                    re.compile(r'(?m)^(version = ")([^"]*)(")'),
                    literal,
                ),
            ),
        ),
        Target(
            ROOT / "core" / "Cargo.toml",
            (
                Rule(
                    "[package] version",
                    re.compile(r'(?m)^(version = ")([^"]*)(")'),
                    literal,
                ),
            ),
        ),
        Target(
            # iOS is owned by another engineer: MARKETING_VERSION and nothing
            # else in this file is ever touched here.
            ROOT / "ios" / "project.yml",
            (
                Rule(
                    "MARKETING_VERSION",
                    re.compile(r'(?m)^(\s*MARKETING_VERSION:\s*")([^"]*)(")'),
                    literal,
                ),
            ),
        ),
        Target(
            ROOT / "android" / "app" / "build.gradle.kts",
            (
                Rule(
                    "versionName",
                    re.compile(r'(?m)^(\s*versionName\s*=\s*")([^"]*)(")'),
                    literal,
                ),
                Rule(
                    "versionCode",
                    re.compile(r"(?m)^(\s*versionCode\s*=\s*)(\d+)()"),
                    lambda version: str(version_code(version)),
                ),
            ),
            optional=True,
        ),
    ]


# ---------------------------------------------------------------------------
# Apply / check
# ---------------------------------------------------------------------------


def relative(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def process(version: str, *, check_only: bool) -> tuple[list[str], list[str]]:
    """Returns (messages, problems). `problems` non-empty means "drift"."""
    messages: list[str] = []
    problems: list[str] = []

    for target in targets():
        name = relative(target.path)
        if not target.path.exists():
            if target.optional:
                messages.append(f"  skip   {name} (not in the tree yet)")
                continue
            problems.append(f"missing file: {name}")
            continue

        text = target.path.read_text(encoding="utf-8")
        original = text
        changed: list[str] = []

        for rule in target.rules:
            wanted = rule.value(version)
            match = rule.pattern.search(text)
            if match is None:
                problems.append(f"{name}: could not find `{rule.label}`")
                continue
            found = match.group(2)
            if found == wanted:
                continue
            if check_only:
                problems.append(
                    f"{name}: {rule.label} is {found} but release.json says {wanted}"
                )
                continue
            text = rule.pattern.sub(
                lambda m, w=wanted: f"{m.group(1)}{w}{m.group(3)}", text, count=1
            )
            changed.append(f"{rule.label} {found} -> {wanted}")

        if check_only:
            continue
        if text != original:
            target.path.write_text(text, encoding="utf-8")
            messages.append(f"  set    {name}: {', '.join(changed)}")
        else:
            messages.append(f"  ok     {name}")

    return messages, problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Sync the release version into every platform manifest."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="do not write anything; exit non-zero when a manifest has drifted",
    )
    parser.add_argument(
        "--version",
        help="set release/release.json to this version first (MAJOR.MINOR.PATCH)",
    )
    args = parser.parse_args()

    if args.version:
        if args.check:
            parser.error("--version cannot be combined with --check")
        parse_semver(args.version)
        if write_release_version(args.version):
            print(f"release/release.json -> {args.version}")

    version = read_release_version()
    print(f"release version: {version} (android versionCode {version_code(version)})")

    messages, problems = process(version, check_only=args.check)
    for line in messages:
        print(line)

    if problems:
        print()
        print("version drift:" if args.check else "could not sync:")
        for problem in problems:
            print(f"  ! {problem}")
        if args.check:
            print()
            print("Run `python3 scripts/bump_version.py` and commit the result.")
        return 1

    print("everything matches release/release.json" if args.check else "done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
