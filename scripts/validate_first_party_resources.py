#!/usr/bin/env python3
"""Validate first-party EN/VI resource parity and reject common encoding damage."""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (ROOT / "app", ROOT / "core", ROOT / "feature")
RESOURCE_KINDS = {"string", "string-array", "plurals"}
MOJIBAKE_MARKERS = ("â€¦", "â€¢", "â€“", "â€”", "Ã", "�")
UI_LITERAL = re.compile(
    r'(?:Text\(\s*"|contentDescription\s*=\s*"|label\s*=\s*"|'
    r'title\s*=\s*"|summary\s*=\s*"|message\s*=\s*")'
)


def translatable_names(path: pathlib.Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {
        element.attrib["name"]
        for element in root
        if element.tag in RESOURCE_KINDS
        and element.attrib.get("name")
        and element.attrib.get("translatable", "true") != "false"
    }


def source_files() -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for source_root in SOURCE_ROOTS:
        files.extend(source_root.glob("*/src/main/**/*.kt"))
        files.extend(source_root.glob("*/src/main/**/*.xml"))
        files.extend(source_root.glob("src/main/**/*.kt"))
        files.extend(source_root.glob("src/main/**/*.xml"))
    return sorted(set(files))


def main() -> int:
    failures: list[str] = []
    resource_sets = 0
    for source_root in SOURCE_ROOTS:
        for english in source_root.glob("**/src/main/res/values/strings.xml"):
            vietnamese = english.parent.parent / "values-vi" / "strings.xml"
            if not vietnamese.exists():
                failures.append(f"Missing Vietnamese resource file for {english.relative_to(ROOT)}")
                continue
            resource_sets += 1
            missing = sorted(translatable_names(english) - translatable_names(vietnamese))
            if missing:
                failures.append(
                    f"{vietnamese.relative_to(ROOT)} is missing: {', '.join(missing)}"
                )

    ui_literal_locations: list[str] = []
    for path in source_files():
        text = path.read_text(encoding="utf-8")
        for marker in MOJIBAKE_MARKERS:
            if marker in text:
                failures.append(
                    f"Encoding marker {marker!r} in {path.relative_to(ROOT)}"
                )
        if path.suffix == ".kt":
            for line_number, line in enumerate(text.splitlines(), start=1):
                if UI_LITERAL.search(line):
                    ui_literal_locations.append(f"{path.relative_to(ROOT)}:{line_number}")

    if failures:
        print("First-party resource validation failed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1

    print(f"RESOURCE_PARITY: PASS ({resource_sets} EN/VI resource sets)")
    print("ENCODING_VALIDATION: PASS")
    print(
        "UI_LITERAL_DEBT: "
        f"{len(ui_literal_locations)} candidate lines (reported for staged migration; "
        "new UI copy should use resources)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
