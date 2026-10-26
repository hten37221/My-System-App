#!/usr/bin/env python3
"""Capability-driven UI smoke harness for the vehicle-control AVD.

The harness deliberately records evidence instead of turning an unavailable or
non-writable property into a false PASS.  It is intentionally independent from
the app's test APK so the preinstalled privileged system app can be exercised.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass


@dataclass(frozen=True)
class Row:
    feature: str
    title: str
    property_id: str
    kind: str
    access: str = "READ_WRITE"
    area_hint: int | None = None


def rows() -> list[Row]:
    da = [
        ("Electronic stability control", "0x1120040E", "switch"),
        ("Automatic emergency braking", "0x11201000", "switch"),
        ("Forward collision warning", "0x11201002", "switch"),
        ("Blind spot warning", "0x11201004", "switch"),
        ("Lane departure warning", "0x11201006", "switch"),
        ("Lane keep assist", "0x11201008", "switch"),
        ("Lane centering assist", "0x1120100A", "switch"),
        ("Emergency lane keep assist", "0x1120100D", "switch"),
        ("Cruise control", "0x1120100F", "switch"),
        ("Hands-on detection", "0x11201016", "switch"),
        ("Driver attention system", "0x11201019", "switch"),
        ("Drowsiness warning", "0x1120101B", "switch"),
        ("Driver distraction system", "0x1120101D", "switch"),
        ("Distraction warning", "0x1120101F", "switch"),
        ("Low-speed collision warning", "0x11201021", "switch"),
        ("Cross-traffic monitoring", "0x11201023", "switch"),
        ("Low-speed emergency braking", "0x11201025", "switch"),
        ("Lane warning sensitivity", "0x21406000", "enum"),
        ("Steering intervention strength", "0x21406001", "enum"),
        ("Lane alert mode", "0x21406002", "enum"),
        ("Collision warning timing", "0x21406003", "enum"),
        ("ACC acceleration profile", "0x21406004", "enum"),
        ("Speed-limit warning", "0x21206005", "switch"),
        ("Speed-limit offset", "0x21406006", "slider"),
        ("Driver warning volume", "0x21406007", "slider"),
        ("Front parking assistance", "0x21206008", "switch"),
        ("Rear parking assistance", "0x21206009", "switch"),
        ("Parking warning volume", "0x2140600A", "slider"),
        ("Safe exit assist", "0x2120600B", "switch"),
        ("Driver-assistance profile", "0x2140600C", "enum"),
    ]
    seat = [
        ("Fore and aft", "0x15400B85", "slider"),
        ("Seat height", "0x15400B8B", "slider"),
        ("Cushion depth", "0x15400B8D", "slider"),
        ("Cushion tilt", "0x15400B8F", "slider"),
        ("Primary backrest angle", "0x15400B87", "slider"),
        ("Secondary backrest angle", "0x15400B89", "slider"),
        ("Lumbar fore and aft", "0x15400B91", "slider"),
        ("Lumbar height", "0x15400BA1", "slider"),
        ("Lumbar side support", "0x15400B93", "slider"),
        ("Cushion side support", "0x15400B9F", "slider"),
        ("Headrest height", "0x15400BA4", "slider"),
        ("Headrest angle", "0x15400B97", "slider"),
        ("Headrest fore and aft", "0x15400B99", "slider"),
        ("Easy access", "0x15200B9D", "switch"),
        ("Seat occupancy", "0x15400BB0", "status", "READ_ONLY"),
        ("Seat belt", "0x15200B82", "status", "READ_WRITE"),
        ("Recall seat memory", "0x15400B80", "slider", "WRITE_ONLY"),
        ("Save seat memory", "0x15400B81", "slider", "WRITE_ONLY"),
        ("Steering-wheel reach", "0x11400BE0", "slider"),
        ("Steering-wheel height", "0x11400BE2", "slider"),
    ]
    lighting = [
        ("Headlights", "0x11400E10", "enum"),
        ("High beam", "0x11400E11", "enum"),
        ("Fog lights", "0x11400E12", "enum"),
        ("Hazard lights", "0x11400E13", "enum"),
        ("Cabin lights", "0x11400F02", "enum"),
        ("Reading lights", "0x15400F04", "enum"),
        ("Steering-wheel lights", "0x11400F0D", "enum"),
        ("Footwell lights", "0x15400B9C", "enum"),
    ]
    doors = [
        ("Door lock", "0x16200B02", "switch"),
        ("Child lock", "0x16200B03", "switch"),
        ("Door position", "0x16400B00", "slider"),
        ("Door movement", "0x16400B01", "enum"),
        ("Window lock", "0x13200BC4", "switch"),
        ("Window position", "0x13400BC0", "slider"),
        ("Window movement", "0x13400BC1", "enum"),
        ("Mirror lock", "0x11200B44", "switch"),
        ("Fold mirror", "0x11200B45", "switch"),
        ("Automatic mirror fold", "0x14200B46", "switch"),
        ("Automatic reverse tilt", "0x14200B47", "switch"),
        ("Mirror vertical position", "0x14400B40", "slider"),
        ("Mirror horizontal position", "0x14400B42", "slider"),
    ]
    hvac = [
        ("Climate power", "0x15200510", "switch"),
        ("Automatic climate", "0x1520050A", "switch"),
        ("Dual / Sync", "0x15200509", "switch"),
        ("Temperature units", "0x1140050E", "enum"),
        ("Set temperature", "0x15600503", "slider"),
        ("Current temperature", "0x15600502", "status", "READ_ONLY"),
        ("Fan speed", "0x15400500", "slider"),
        ("Fan direction", "0x15400501", "enum"),
        ("A/C", "0x15200505", "switch"),
        ("Max A/C", "0x15200506", "switch"),
        ("Recirculation", "0x15200508", "switch"),
        ("Automatic recirculation", "0x15200512", "switch"),
        # VehicleAreaWindow uses FRONT_WINDSHIELD=0x1 and REAR_WINDSHIELD=0x2.
        # Keep the hints aligned with the platform constants; 0x4 is not a
        # windshield area on the API 37 automotive image.
        ("Front defroster", "0x13200504", "switch", "READ_WRITE", 0x1),
        ("Rear defroster", "0x13200504", "switch", "READ_WRITE", 0x2),
        ("Max defrost", "0x15200507", "switch"),
        ("Mirror heat", "0x1440050C", "switch"),
        ("Seat heating / cooling", "0x1540050B", "slider"),
        ("Seat ventilation", "0x15400513", "slider"),
        ("Steering-wheel heat", "0x1140050D", "slider"),
    ]

    result: list[Row] = []
    for feature, values in (
        ("driver", da),
        ("seat", seat),
        ("lighting", lighting),
        ("door", doors),
        ("hvac", hvac),
    ):
        result.extend(Row(feature, *value) for value in values)
    return result


class Harness:
    def __init__(self, serial: str, out: pathlib.Path, adb_bin: str):
        self.serial = serial
        self.out = out
        self.adb = adb_bin
        self.out.mkdir(parents=True, exist_ok=True)
        self.counter = 0

    def call(self, *args: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.adb, "-s", self.serial, *args],
            text=True,
            capture_output=True,
            timeout=timeout,
        )

    def shell(self, *args: str) -> str:
        return self.call("shell", *args).stdout

    def dump(self, name: str) -> ET.Element:
        path = f"/sdcard/vehicle-ui-{self.counter}.xml"
        self.counter += 1
        self.call("shell", "uiautomator", "dump", path)
        xml = self.call("shell", "cat", path).stdout
        local = self.out / f"{name}-{self.counter}.xml"
        local.write_text(xml, encoding="utf-8")
        return ET.fromstring(xml)

    @staticmethod
    def parents(root: ET.Element) -> dict[int, ET.Element]:
        return {id(child): node for node in root.iter() for child in node}

    @staticmethod
    def bounds(node: ET.Element) -> tuple[int, int, int, int]:
        return tuple(int(x) for x in re.findall(r"\d+", node.attrib["bounds"]))  # type: ignore[return-value]

    def text_node(self, root: ET.Element, title: str) -> ET.Element | None:
        return next((n for n in root.iter("node") if n.attrib.get("text") == title), None)

    def control_node(self, root: ET.Element, title: str) -> ET.Element | None:
        title_node = self.text_node(root, title)
        if title_node is None:
            return None
        # Compose exposes the title and its control as siblings under the row
        # container.  Looking only at ancestors therefore misses every
        # SeekBar and most switches.  Walk row ancestors and prefer a real
        # control descendant; ignore the separate information-button child.
        parent = self.parents(root).get(id(title_node))
        nearest_parent = parent
        while parent is not None:
            descendants = list(parent.iter("node"))
            title_bottom = self.bounds(title_node)[3]
            seeks = sorted(
                (
                    n
                    for n in descendants
                    if n.attrib.get("class") == "android.widget.SeekBar"
                    # A slider row is laid out as title → value/description →
                    # seek bar.  Reject bars above the title: those belong to
                    # a preceding row when the current lazy-list row is
                    # clipped at the viewport edge.
                    and self.bounds(n)[1] >= title_bottom
                ),
                key=lambda n: self.bounds(n)[1] - title_bottom,
            )
            seek = seeks[0] if seeks else None
            if seek is not None:
                # A parent section can contain several sliders.  Only bind
                # the title to the nearest visible SeekBar; if the row's own
                # bar is clipped, keep searching/swiping instead of editing a
                # neighbouring control.
                distance = self.bounds(seek)[1] - title_bottom
                if distance <= 360:
                    return seek
            # A checkable/clickable control must be in the title's immediate
            # row container.  Searching ancestor sections can select a
            # neighbouring switch or information button for a clipped slider.
            if parent is nearest_parent:
                info_desc = f"Information about {title}"
                checkable = next(
                    (
                        n
                        for n in descendants
                        if n is not title_node
                        and n.attrib.get("checkable") == "true"
                        # The row itself is checkable and may contain the
                        # separate information affordance as a child.  Do
                        # not reject the row merely because that affordance
                        # is present; reject only the affordance node.
                        and n.attrib.get("content-desc", "") != info_desc
                    ),
                    None,
                )
                if checkable is not None:
                    return checkable
                clickable = next(
                    (
                        n
                        for n in descendants
                        if n is not title_node
                        and n.attrib.get("clickable") == "true"
                        and n.attrib.get("content-desc", "") != info_desc
                        # The information icon is wrapped by its own
                        # clickable container whose content description is
                        # empty.  A real enum row can also contain that icon,
                        # so reject only the narrow icon-sized wrapper rather
                        # than the full-width row.
                        and not (
                            any(
                                child.attrib.get("content-desc") == info_desc
                                for child in n.iter("node")
                            )
                            and self.bounds(n)[2] - self.bounds(n)[0] <= 300
                        )
                    ),
                    None,
                )
                if clickable is not None:
                    return clickable
            parent = self.parents(root).get(id(parent))
        return None

    def tap_node(self, node: ET.Element) -> None:
        left, top, right, bottom = self.bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def enum_value_node(self, root: ET.Element, title: str) -> ET.Element | None:
        """Return the trailing value semantics node for a Compose enum row."""
        title_node = self.text_node(root, title)
        if title_node is None:
            return None
        parents = self.parents(root)
        parent = parents.get(id(title_node))
        while parent is not None:
            candidates = []
            for node in parent.iter("node"):
                text = node.attrib.get("text", "")
                if (
                    text
                    and node.attrib.get("class") == "android.widget.TextView"
                    and text != title
                    and not text.startswith("Information about ")
                ):
                    left, _, right, _ = self.bounds(node)
                    # Enum values are the trailing text in the row; descriptions
                    # occupy the leading content column.
                    if left > (right - left) * 2:
                        candidates.append(node)
            if candidates:
                return candidates[-1]
            parent = parents.get(id(parent))
        return None

    def feature_root(self, feature: str) -> None:
        # The automotive AVD may start EmbeddedKitchenSinkApp as a privileged
        # overlay window.  It steals accessibility focus and obscures the
        # vehicle-control activity even though MainActivity remains resumed.
        # Stop that test overlay before every feature navigation; force-stop is
        # harmless on images that do not ship the optional package.
        self.shell("am", "force-stop", "com.google.android.car.kitchensink")
        self.shell("am", "force-stop", "com.android.mysystemapp")
        if feature == "driver":
            self.shell("am", "start", "-a", "com.android.mysystemapp.action.OPEN_VEHICLE_CONTROL")
        elif feature == "hvac":
            self.shell("am", "start", "-a", "com.android.mysystemapp.action.OPEN_CLIMATE_SETTINGS")
        else:
            # Clear the saved NavController task so a prior domain route does
            # not masquerade as the home screen for seat/light/door runs.
            self.shell(
                "am",
                "start",
                "-f",
                "0x10008000",
                "-n",
                "com.android.mysystemapp/.MainActivity",
            )
            time.sleep(1.5)
            card = {
                "seat": "Seats and steering wheel (Vehicle control)",
                "lighting": "Vehicle lighting (Vehicle control)",
                "door": "Doors, windows and mirrors (Vehicle control)",
            }[feature]
            card_summary = {
                "seat": "Area-aware position, support, memory, occupancy and steering adjustments",
                "lighting": "Exterior, safety, cabin, reading and footwell lighting",
                "door": "Independent lock, position, movement, fold and tilt controls",
            }[feature]
            for _ in range(20):
                root = self.dump("home")
                # The first card can be clipped at the top edge after a
                # previous scroll, leaving only its summary semantics in
                # uiautomator.  The summary resolves the same clickable card.
                node = self.control_node(root, card)
                if node is None:
                    node = self.control_node(root, card_summary)
                if node is not None:
                    _, top, _, bottom = self.bounds(node)
                    # Do not tap a clipped card at the navigation-bar edge;
                    # Compose can route that gesture to the card below it.
                    if bottom > 900:
                        self.shell("input", "swipe", "900", "880", "900", "520", "350")
                        time.sleep(0.35)
                        continue
                    self.tap_node(node)
                    break
                self.shell("input", "swipe", "900", "880", "900", "300", "350")
        # Compose remembers the scroll offset across activity restarts.  Put
        # every feature at a deterministic top before searching a row;
        # otherwise a later row can be missed because the previous row left
        # the screen at the bottom.  Wait for the data-driven screen first,
        # then verify the first section marker after each reset gesture.
        top_marker = {
            "driver": "Vehicle stability",
            "seat": "Seat position",
            "lighting": "Exterior lighting",
            "door": "Doors and locks",
            "hvac": "Climate power",
        }[feature]
        time.sleep(2.0)
        for _ in range(14):
            root = self.dump("reset")
            if self.text_node(root, top_marker) is not None:
                break
            self.shell("input", "swipe", "900", "260", "900", "900", "180")
            time.sleep(0.15)
        time.sleep(2.0)

    def find_row(self, row: Row) -> tuple[ET.Element | None, ET.Element]:
        self.feature_root(row.feature)
        # Use deliberately short, slow scrolls so a Compose fling cannot
        # jump over a row between two hierarchy dumps.
        for _ in range(20):
            root = self.dump(row.feature)
            node = self.control_node(root, row.title)
            if node is not None:
                return node, root
            # Keep the gesture in the content column.  The previous x=1800
            # worked on the 3904px ultrawide AVD but lands on the trailing
            # info affordance at the 1920px test resolution.
            self.shell("input", "swipe", "900", "880", "900", "500", "600")
            time.sleep(0.25)
        return None, root

    def car_get(self, prop: str, area: int) -> str:
        return self.shell("cmd", "car_service", "get-property-value", prop, hex(area)).strip()

    def car_set(self, prop: str, area: int, value: str) -> str:
        return self.shell("cmd", "car_service", "set-property-value", prop, hex(area), value).strip()

    def car_config_areas(self, prop: str) -> list[int]:
        text = self.shell("cmd", "car_service", "get-carpropertyconfig", prop)
        return [int(value, 16) for value in re.findall(r"areaId:[^()]+\((0x[0-9a-fA-F]+)\)", text)] or [0]

    @staticmethod
    def scalar(value: str) -> str | None:
        m = re.search(r"Value:\s*([^\s}]+)", value)
        if not m:
            return None
        token = m.group(1)
        if token.upper() == "TRUE":
            return "true"
        if token.upper() == "FALSE":
            return "false"
        enum = re.search(r"\((0x[0-9a-fA-F]+)\)", token)
        return str(int(enum.group(1), 16)) if enum else token

    @staticmethod
    def alternate(kind: str, current: str | None, config: str) -> str | None:
        if kind == "switch":
            return "false" if current == "true" else "true"
        if kind == "enum":
            # Some AOSP properties (notably HVAC temperature units) publish
            # their legal values in configArray rather than i min/i max.
            # Prefer a different advertised value over inventing 0/1.
            values = re.search(r"configArray:\[([^\]]*)\]", config)
            if values:
                advertised = [int(token) for token in re.findall(r"[-+]?\d+", values.group(1))]
                now = int(float(current)) if current is not None else None
                for value in advertised:
                    if value != now:
                        return str(value)
            values = re.search(r"i min:([-+]?\d+), i max:([-+]?\d+)", config)
            if values:
                low, high = int(values.group(1)), int(values.group(2))
                now = int(float(current or low))
                if low == high:
                    return "1" if now != 1 else "0"
                return str(now + 1 if now < high else now - 1)
            return None
        if kind == "slider":
            values = re.search(r"i min:([-+]?\d+), i max:([-+]?\d+)", config)
            if values:
                low, high = int(values.group(1)), int(values.group(2))
                # A zero-width integer range is how CarService reports a
                # FLOAT property on some emulator builds.  Keep searching
                # for f min/f max instead of treating it as unavailable.
                if low != high:
                    now = int(float(current or low))
                    return str(now + 1 if now < high else now - 1)
            # FLOAT properties expose f min/f max.  Use a conservative
            # half-unit step (or a smaller step near the upper bound) so the
            # action remains within the advertised range and is accepted by
            # FakeVehicleHardware.
            values = re.search(r"f min:([-+]?\d+(?:\.\d+)?), f max:([-+]?\d+(?:\.\d+)?)", config)
            if values:
                low, high = float(values.group(1)), float(values.group(2))
                now = float(current or low)
                if low == high:
                    return None
                step = min(0.5, max(0.1, (high - low) / 30.0))
                target = now + step if now + step <= high else now - step
                return f"{target:.3f}".rstrip("0").rstrip(".")
            return None
        return None

    def interact(self, row: Row, node: ET.Element, root: ET.Element, original: str | None) -> dict[str, object]:
        result: dict[str, object] = {"ui": "NOT_RUN", "external": "NOT_RUN", "restore": "NOT_RUN"}
        if row.access != "READ_WRITE":
            result.update({"ui": "N/A_WRITE_ONLY" if row.access == "WRITE_ONLY" else "N/A_READ_ONLY"})
            return result
        if node.attrib.get("enabled") == "false":
            result["ui"] = "UNAVAILABLE"
            return result
        prop = row.property_id
        area = row.area_hint if row.area_hint is not None else (self.car_config_areas(prop) or [0])[0]
        config = self.shell("cmd", "car_service", "get-carpropertyconfig", prop)
        target = self.alternate(row.kind, original, config)
        if target is None:
            result["ui"] = "UNAVAILABLE_NO_ALTERNATE"
            return result
        if row.kind == "switch":
            self.tap_node(node)
        elif row.kind == "enum":
            title_node = next(
                (candidate for candidate in root.iter("node") if candidate.attrib.get("text") == row.title),
                node,
            )
            _, title_bottom, _, _ = self.bounds(title_node)
            # HVAC uses inline FilterChips for option properties.  They do
            # not expose a trailing value or popup; click the nearby chip
            # containers and wait for the VHAL value to change.
            inline_options = []
            for candidate in root.iter("node"):
                attrs = candidate.attrib
                if attrs.get("clickable") != "true" or attrs.get("enabled") == "false":
                    continue
                left, top, right, bottom = self.bounds(candidate)
                if (
                    title_bottom + 4 <= top <= title_bottom + 180
                    and right - left <= 520
                    and left < 1200
                    and bottom > top
                ):
                    inline_options.append(candidate)
            if inline_options:
                changed = False
                for option in inline_options:
                    option_text = " ".join(
                        child.attrib.get("text", "") for child in option.iter("node")
                    )
                    # A few FakeVehicleHardware enum properties intentionally
                    # advertise an unwritable state (for example FAN_DIRECTION
                    # value 0).  Trying that chip first puts the app into its
                    # error state and disables all remaining options.  Skip
                    # those states and the already-selected value.
                    option_value = re.search(r"(?:^|\s)(-?\d+)(?:\s|$)", option_text)
                    blocked = {
                        int(value)
                        for value in re.findall(r"unwritable states:\s*\[([^\]]*)\]", config)
                        for value in re.findall(r"-?\d+", value)
                    }
                    if option_value and (
                        int(option_value.group(1)) in blocked
                        or str(int(option_value.group(1))) == str(original)
                    ):
                        continue
                    self.tap_node(option)
                    time.sleep(0.25)
                    if self.scalar(self.car_get(prop, area)) != original:
                        changed = True
                        break
                if not changed:
                    result["ui"] = "NO_VALUE_CHANGE"
                    return result
                # Continue to the common external-mutation/restore checks.
                time.sleep(1.0)
                after_ui = self.scalar(self.car_get(prop, area))
                result["ui"] = "PASS" if after_ui is not None and after_ui != original else "NO_VALUE_CHANGE"
                if after_ui is None:
                    return result
                external_target = original if original is not None else target
                self.car_set(prop, area, external_target)
                time.sleep(1.0)
                restored = self.scalar(self.car_get(prop, area))
                result["external"] = "PASS" if restored == external_target else "FAIL"
                result["restore"] = "PASS" if restored == original else "FAIL"
                return result
            # HVAC fan direction and similar rows use a non-clickable
            # HorizontalScrollView of Compose semantics TextViews.  The
            # option text is still a real touch target, so tap each visible
            # option until CarService reports a different value.
            inline_text_options = []
            for container in root.iter("node"):
                if container.attrib.get("class") != "android.widget.HorizontalScrollView":
                    continue
                left, top, right, bottom = self.bounds(container)
                if not (title_bottom + 4 <= top <= title_bottom + 220 and bottom > top):
                    continue
                inline_text_options.extend(
                    candidate
                    for candidate in container.iter("node")
                    if candidate.attrib.get("class") == "android.widget.TextView"
                    and candidate.attrib.get("text")
                    and candidate.attrib.get("enabled") != "false"
                )
            if len(inline_text_options) > 1:
                changed = False
                for option in inline_text_options:
                    option_text = option.attrib.get("text", "")
                    option_value = re.search(r"-?\d+", option_text)
                    blocked = {
                        int(value)
                        for value in re.findall(r"unwritable states:\s*\[([^\]]*)\]", config)
                        for value in re.findall(r"-?\d+", value)
                    }
                    if option_value and (
                        int(option_value.group(0)) in blocked
                        or option_value.group(0) == str(original)
                    ):
                        continue
                    self.tap_node(option)
                    time.sleep(0.25)
                    if self.scalar(self.car_get(prop, area)) != original:
                        changed = True
                        break
                if not changed:
                    result["ui"] = "NO_VALUE_CHANGE"
                    return result
                time.sleep(1.0)
                after_ui = self.scalar(self.car_get(prop, area))
                result["ui"] = "PASS" if after_ui is not None and after_ui != original else "NO_VALUE_CHANGE"
                if after_ui is None:
                    return result
                external_target = original if original is not None else target
                self.car_set(prop, area, external_target)
                time.sleep(1.0)
                restored = self.scalar(self.car_get(prop, area))
                result["external"] = "PASS" if restored == external_target else "FAIL"
                result["restore"] = "PASS" if restored == original else "FAIL"
                return result
            # Tapping the visible value is more reliable than tapping the
            # whole row at the bottom edge of a lazy list.  The latter can
            # resolve to the neighbouring info affordance on Compose.
            value_node = self.enum_value_node(root, row.title)
            self.tap_node(value_node if value_node is not None else node)
            time.sleep(0.25)
            menu = self.dump("menu")
            # Material/Compose menus expose the option semantics via
            # content-desc on android.view.View, while clickable remains
            # false in uiautomator.  Bounds are still actionable.
            options = [
                n
                for n in menu.iter("node")
                if n.attrib.get("content-desc")
                and not n.attrib.get("content-desc", "").startswith("Information about ")
            ]
            if not options:
                result["ui"] = "NO_ENUM_OPTION"
                return result
            # Try each visible option until the VHAL value changes.  This
            # handles enum rows whose first menu item is already selected
            # (e.g. Headlights=Automatic).
            changed = False
            for index, option in enumerate(options):
                self.tap_node(option)
                time.sleep(0.25)
                if self.scalar(self.car_get(prop, area)) != original:
                    changed = True
                    break
                if index + 1 < len(options):
                    self.tap_node(node)
                    time.sleep(0.15)
                    menu = self.dump("menu")
                    options = [
                        n
                        for n in menu.iter("node")
                        if n.attrib.get("content-desc")
                        and not n.attrib.get("content-desc", "").startswith("Information about ")
                    ]
            if not changed:
                result["ui"] = "NO_VALUE_CHANGE"
                return result
        elif row.kind == "slider":
            left, top, right, bottom = self.bounds(node)
            now = float(original or 0)
            nums = re.search(r"i min:([-+]?\d+), i max:([-+]?\d+)", config)
            if nums and int(nums.group(1)) != int(nums.group(2)):
                low, high = int(nums.group(1)), int(nums.group(2))
            else:
                float_nums = re.search(
                    r"f min:([-+]?\d+(?:\.\d+)?), f max:([-+]?\d+(?:\.\d+)?)",
                    config,
                )
                low, high = (
                    (float(float_nums.group(1)), float(float_nums.group(2)))
                    if float_nums
                    else (0.0, 100.0)
                )
            target_value = float(target)
            # Keep the gesture a few pixels inside the accessibility bounds.
            # AOSP's Compose SeekBar can ignore an exact screen-edge tap,
            # which is common for two-state door-position ranges (0..1).
            usable_left = left + min(24, max(1, (right - left) // 10))
            usable_right = right - min(24, max(1, (right - left) // 10))
            usable_width = max(1, usable_right - usable_left)
            x0 = int(usable_left + (now - low) / max(1, high - low) * usable_width)
            x1 = int(usable_left + (target_value - low) / max(1, high - low) * usable_width)
            self.shell("input", "swipe", str(x0), str((top + bottom) // 2), str(x1), str((top + bottom) // 2), "350")
            # Some Compose accessibility bridges expose the SeekBar but do
            # not deliver a short swipe at the thumb.  A tap at the target
            # coordinate is an equivalent user action and fixes that bridge
            # quirk without writing the property externally.
            time.sleep(0.25)
            if self.scalar(self.car_get(prop, area)) == original:
                self.shell("input", "tap", str(x1), str((top + bottom) // 2))
        time.sleep(1.0)
        after_ui = self.scalar(self.car_get(prop, area))
        result["ui"] = "PASS" if after_ui is not None and after_ui != original else "NO_VALUE_CHANGE"
        if after_ui is None:
            return result
        # External mutation is deliberately performed through CarService, not through app state.
        external_target = original if original is not None else target
        if row.kind == "switch":
            external_target = "true" if after_ui == "false" else "false"
        elif row.kind in {"enum", "slider"}:
            external_target = original
        if external_target is None:
            return result
        self.car_set(prop, area, external_target)
        time.sleep(1.0)
        restored = self.scalar(self.car_get(prop, area))
        result["external"] = "PASS" if restored == external_target else "FAIL"
        result["restore"] = "PASS" if restored == original else "FAIL"
        return result

    def run(
        self,
        selected_features: set[str] | None = None,
        limit: int | None = None,
        selected_properties: set[str] | None = None,
    ) -> pathlib.Path:
        report: dict[str, object] = {
            "schema": "vehicle-control-ui-matrix-v1",
            "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
            "serial": self.serial,
            "results": [],
        }
        candidates = [
            row
            for row in rows()
            if (selected_features is None or row.feature in selected_features)
            and (selected_properties is None or row.property_id.lower() in selected_properties)
        ]
        for row in candidates[:limit]:
            entry: dict[str, object] = {
                "feature": row.feature,
                "title": row.title,
                "property_id": row.property_id,
                "kind": row.kind,
                "area_hint": row.area_hint,
            }
            try:
                node, root = self.find_row(row)
                entry["visible"] = node is not None
                entry["info_semantics"] = any(
                    n.attrib.get("content-desc") == f"Information about {row.title}"
                    for n in root.iter("node")
                )
                if node is None:
                    entry["status"] = "NOT_VISIBLE"
                else:
                    area = row.area_hint if row.area_hint is not None else (self.car_config_areas(row.property_id) or [0])[0]
                    original = self.scalar(self.car_get(row.property_id, area))
                    entry["area"] = area
                    entry["initial"] = original
                    entry.update(self.interact(row, node, root, original))
                    entry["status"] = "PASS" if entry.get("ui") == "PASS" and entry.get("restore") == "PASS" else str(entry.get("ui"))
            except (OSError, subprocess.SubprocessError, ET.ParseError, ValueError) as exc:
                entry["status"] = "ERROR"
                entry["error"] = repr(exc)
            report["results"].append(entry)  # type: ignore[union-attr]
            print(json.dumps(entry, ensure_ascii=False), flush=True)
        path = self.out / "ui-matrix.json"
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    parser.add_argument("output")
    parser.add_argument("--adb", default="adb")
    parser.add_argument(
        "--feature",
        action="append",
        choices=("driver", "seat", "lighting", "door", "hvac"),
        help="limit the smoke run to one or more feature domains",
    )
    parser.add_argument("--limit", type=int, help="limit the number of catalog rows after filtering")
    parser.add_argument(
        "--property",
        action="append",
        dest="properties",
        help="limit the smoke run to a property ID; repeat for multiple IDs",
    )
    args = parser.parse_args()
    selected_features = set(args.feature) if args.feature else None
    selected_properties = {value.lower() for value in (args.properties or [])}
    path = Harness(args.serial, pathlib.Path(args.output), args.adb).run(
        selected_features,
        args.limit,
        selected_properties or None,
    )
    print(f"Evidence written to {path}")


if __name__ == "__main__":
    main()
