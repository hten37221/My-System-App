#!/usr/bin/env python3
"""Validate the self-contained B-Material local Maven repository."""

from __future__ import annotations

import hashlib
import json
import sys
import xml.etree.ElementTree as element_tree
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "libs" / "bmaterial"
MAVEN = BUNDLE / "maven"
MANIFEST = BUNDLE / "LOCAL_MAVEN_MANIFEST.json"
CHECKSUMS = BUNDLE / "SHA256SUMS.txt"
GROUP = "com.b231001.bmaterial"
VERSION = "1.3.1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_checksums() -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in CHECKSUMS.read_text(encoding="utf-8").splitlines():
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            raise ValueError(f"Invalid checksum entry: {line!r}")
        entries[parts[1].lstrip("*")] = parts[0].lower()
    return entries


def local_repository_path(artifact: str, extension: str) -> Path:
    return MAVEN / GROUP.replace(".", "/") / artifact / VERSION / f"{artifact}-{VERSION}.{extension}"


def validate_pom(path: Path, artifact: str, errors: list[str]) -> None:
    try:
        root = element_tree.parse(path).getroot()
        namespace = "{http://maven.apache.org/POM/4.0.0}"
        group = root.findtext(f"{namespace}groupId")
        name = root.findtext(f"{namespace}artifactId")
        version = root.findtext(f"{namespace}version")
        packaging = root.findtext(f"{namespace}packaging")
    except element_tree.ParseError as error:
        errors.append(f"Invalid POM {path}: {error}")
        return
    if (group, name, version, packaging) != (GROUP, artifact, VERSION, "aar"):
        errors.append(
            f"POM identity mismatch for {artifact}: "
            f"{group}:{name}:{version}:{packaging}",
        )


def validate_module(path: Path, artifact: str, errors: list[str]) -> None:
    try:
        module = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"Invalid Gradle module metadata {path}: {error}")
        return
    component = module.get("component", {})
    identity = (component.get("group"), component.get("module"), component.get("version"))
    if identity != (GROUP, artifact, VERSION):
        errors.append(f"Module identity mismatch for {artifact}: {identity}")


def main() -> int:
    errors: list[str] = []
    if not MANIFEST.is_file():
        print(f"FAIL: missing {MANIFEST}")
        return 1
    if not CHECKSUMS.is_file():
        print(f"FAIL: missing {CHECKSUMS}")
        return 1

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if (manifest.get("group"), manifest.get("version")) != (GROUP, VERSION):
        errors.append("Manifest group/version is not com.b231001.bmaterial:1.3.1")

    artifacts = manifest.get("artifacts", [])
    checksums = load_checksums()
    if len(artifacts) != 28:
        errors.append(f"Manifest must describe 28 raw AARs, found {len(artifacts)}")
    if len(checksums) != 28:
        errors.append(f"SHA256SUMS must describe 28 raw AARs, found {len(checksums)}")

    published: set[str] = set()
    raw_names: set[str] = set()
    for entry in artifacts:
        artifact = entry.get("artifact")
        raw_name = entry.get("rawFile")
        expected_hash = entry.get("sha256", "").lower()
        status = entry.get("status")
        if not isinstance(artifact, str) or not isinstance(raw_name, str):
            errors.append(f"Invalid manifest entry: {entry!r}")
            continue
        if raw_name in raw_names:
            errors.append(f"Duplicate raw artifact in manifest: {raw_name}")
        raw_names.add(raw_name)
        if status not in {"published", "archive-only"}:
            errors.append(f"Invalid status for {artifact}: {status!r}")
        checksum_hash = checksums.get(raw_name)
        if checksum_hash != expected_hash:
            errors.append(f"Checksum manifest mismatch for {raw_name}")
        raw_path = BUNDLE / raw_name
        if status != "published":
            if not raw_path.is_file():
                errors.append(f"Missing archive-only AAR: {raw_path}")
            elif sha256(raw_path) != expected_hash:
                errors.append(f"Archive-only AAR SHA-256 mismatch: {raw_name}")
            if local_repository_path(artifact, "aar").exists():
                errors.append(f"Archive-only artifact must not be published: {artifact}")
            continue

        if raw_path.exists():
            errors.append(
                f"Published artifact has a redundant top-level AAR copy: {raw_name}",
            )
        published.add(artifact)
        repo_aar = local_repository_path(artifact, "aar")
        repo_pom = local_repository_path(artifact, "pom")
        repo_module = local_repository_path(artifact, "module")
        for path in (repo_aar, repo_pom, repo_module):
            if not path.is_file():
                errors.append(f"Missing published repository file: {path.relative_to(ROOT)}")
        if repo_aar.is_file() and sha256(repo_aar) != expected_hash:
            errors.append(f"Published AAR SHA-256 mismatch: {artifact}")
        if repo_pom.is_file():
            validate_pom(repo_pom, artifact, errors)
        if repo_module.is_file():
            validate_module(repo_module, artifact, errors)

    expected_published = {
        "ui-core-tokens",
        "ui-core-resources",
        "ui-core-foundation",
        "ui-components-button",
        "ui-components-dialog",
        "ui-components-card",
        "ui-components-chip",
        "ui-components-indicator",
        "ui-components-layout",
        "ui-components-listitem",
        "ui-components-loading",
        "ui-components-slider",
        "ui-components-snackbar",
        "ui-components-scrollbar",
        "ui-components-switch",
        "ui-components-text",
        "ui-components-textfield",
    }
    if published != expected_published:
        errors.append(
            "Published B-Material set differs from the audited 17-artifact UI graph: "
            f"{sorted(published)}",
        )

    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    for forbidden in (
        "maven.pkg.github.com/binhdz1102/B-Material",
        "gpr.user",
        "gpr.key",
        "GPR_USER",
        "GPR_KEY",
    ):
        if forbidden in settings:
            errors.append(f"settings.gradle.kts still contains forbidden GitHub resolver token: {forbidden}")
    if "BundledBMaterial" not in settings or "exclusiveContent" not in settings:
        errors.append("settings.gradle.kts does not lock B-Material to the bundled local Maven repository")

    if errors:
        print("B-Material local repository validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        "PASS: B-Material local repository "
        f"published={len(published)} archive-only={len(artifacts) - len(published)} "
        "redundant-copies=0",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
