#!/usr/bin/env python3
"""Import approved character icons and workbook relic icons into a resource pack.

Character files are accepted only when their four-digit owner ID exists in the
character catalog. PNG and lossless WebP files are accepted. Relic images are resolved through the XLSX rich-value
metadata chain and must cover every relic in the imported relic catalog.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import re
import shutil
import struct
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
RICH_DATA_NS = "http://schemas.microsoft.com/office/spreadsheetml/2017/richdata"
CHARACTER_ICON = re.compile(r"icon_unit_(\d{4})(\d{2})\.(?:png|webp)", re.IGNORECASE)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def q(namespace: str, name: str) -> str:
    return f"{{{namespace}}}{name}"


def load_json(path: Path) -> dict[str, object]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return document


def image_format_and_dimensions(data: bytes) -> tuple[str, int, int]:
    if len(data) >= 24 and data[:8] == PNG_SIGNATURE and data[12:16] == b"IHDR":
        width, height = struct.unpack(">II", data[16:24])
        image_format = "png"
    elif len(data) >= 25 and data[:4] == b"RIFF" and data[8:16] == b"WEBPVP8L" and data[20] == 0x2F:
        first, second, third, fourth = data[21:25]
        width = 1 + first + ((second & 0x3F) << 8)
        height = 1 + (second >> 6) + (third << 2) + ((fourth & 0x0F) << 10)
        image_format = "webp"
    else:
        raise ValueError("file is not a supported PNG or lossless WebP image")
    if width <= 0 or height <= 0:
        raise ValueError("image dimensions must be positive")
    return image_format, width, height


def asset(owner_id: str, variant: str, relative_path: str, data: bytes) -> dict[str, object]:
    image_format, width, height = image_format_and_dimensions(data)
    return {
        "ownerId": owner_id,
        "variant": variant,
        "file": relative_path,
        "format": image_format,
        "width": width,
        "height": height,
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def copy_character_icons(
    catalog: dict[str, object],
    source: Path,
    pack_root: Path,
) -> tuple[list[dict[str, object]], int]:
    characters = catalog.get("characters")
    if not isinstance(characters, list):
        raise ValueError("character catalog must contain a characters list")
    known_ids = {str(item["id"]) for item in characters if isinstance(item, dict) and item.get("id")}
    result: list[dict[str, object]] = []
    unmatched = 0
    seen: set[tuple[str, str]] = set()
    for source_file in sorted(
        (path for path in source.iterdir() if path.is_file() and path.suffix.lower() in {".png", ".webp"}),
        key=lambda path: path.name.lower(),
    ):
        match = CHARACTER_ICON.fullmatch(source_file.name)
        if match is None:
            unmatched += 1
            continue
        owner_id, variant = match.groups()
        if owner_id not in known_ids:
            unmatched += 1
            continue
        key = owner_id, variant
        if key in seen:
            raise ValueError(f"duplicate character icon: {owner_id}/{variant}")
        seen.add(key)
        data = source_file.read_bytes()
        image_format, _, _ = image_format_and_dimensions(data)
        destination_name = f"{source_file.stem.lower()}.{image_format}"
        relative_path = f"icons/characters/{destination_name}"
        target = pack_root / Path(relative_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_file, target)
        result.append(asset(owner_id, variant, relative_path, data))
    result.sort(key=lambda item: (int(str(item["ownerId"])), str(item["variant"])))
    return result, unmatched


def sheet_targets(archive: ZipFile) -> dict[str, str]:
    relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    targets = {
        item.attrib["Id"]: item.attrib["Target"]
        for item in relationships.findall(q(PACKAGE_REL_NS, "Relationship"))
    }
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    result: dict[str, str] = {}
    sheets = workbook.find(q(MAIN_NS, "sheets"))
    if sheets is None:
        return result
    for sheet in sheets:
        relationship_id = sheet.attrib[q(REL_NS, "id")]
        target = targets[relationship_id]
        result[sheet.attrib["name"]] = target if target.startswith("xl/") else f"xl/{target.lstrip('/')}"
    return result


def rich_image_paths(archive: ZipFile) -> list[str]:
    metadata = ET.fromstring(archive.read("xl/metadata.xml"))
    future_metadata = metadata.find(q(MAIN_NS, "futureMetadata"))
    value_metadata = metadata.find(q(MAIN_NS, "valueMetadata"))
    rich_values = list(ET.fromstring(archive.read("xl/richData/rdrichvalue.xml")))
    rich_relationships = list(ET.fromstring(archive.read("xl/richData/richValueRel.xml")))
    package_relationships = ET.fromstring(archive.read("xl/richData/_rels/richValueRel.xml.rels"))
    relationship_targets = {
        item.attrib["Id"]: item.attrib["Target"]
        for item in package_relationships.findall(q(PACKAGE_REL_NS, "Relationship"))
    }
    if future_metadata is None or value_metadata is None:
        raise ValueError("workbook does not contain rich-value metadata")

    result: list[str] = []
    for value_block in value_metadata:
        reference = value_block.find(q(MAIN_NS, "rc"))
        if reference is None:
            raise ValueError("rich-value metadata reference is missing")
        future_index = int(reference.attrib["v"])
        rich_binding = future_metadata[future_index].find(f".//{q(RICH_DATA_NS, 'rvb')}")
        if rich_binding is None:
            raise ValueError("rich-value binding is missing")
        rich_value = rich_values[int(rich_binding.attrib["i"])]
        identifiers = rich_value.findall(q(RICH_DATA_NS, "v"))
        if not identifiers or identifiers[0].text is None:
            raise ValueError("local image identifier is missing")
        local_image_index = int(identifiers[0].text)
        relationship_id = rich_relationships[local_image_index].attrib[q(REL_NS, "id")]
        target = relationship_targets[relationship_id]
        archive_path = posixpath.normpath(posixpath.join("xl/richData", target))
        if not archive_path.startswith("xl/richData/media/"):
            raise ValueError(f"unsafe rich-value image path: {archive_path}")
        result.append(archive_path)
    return result


def extract_relic_icons(
    relic_catalog: dict[str, object],
    workbook: Path,
    pack_root: Path,
) -> list[dict[str, object]]:
    relics = relic_catalog.get("relics")
    if not isinstance(relics, list):
        raise ValueError("relic catalog must contain a relics list")
    expected_ids = {str(item["id"]) for item in relics if isinstance(item, dict) and item.get("id")}
    result: list[dict[str, object]] = []
    with ZipFile(workbook) as archive:
        target = sheet_targets(archive).get("迷宫遗物")
        if target is None:
            raise ValueError("workbook does not contain the 迷宫遗物 sheet")
        image_paths = rich_image_paths(archive)
        sheet = ET.fromstring(archive.read(target))
        sheet_data = sheet.find(q(MAIN_NS, "sheetData"))
        if sheet_data is None:
            raise ValueError("迷宫遗物 sheet has no rows")
        for row in sheet_data.findall(q(MAIN_NS, "row")):
            cells = {cell.attrib.get("r", "")[0:1]: cell for cell in row.findall(q(MAIN_NS, "c"))}
            id_cell = cells.get("A")
            image_cell = cells.get("B")
            if id_cell is None or image_cell is None or "vm" not in image_cell.attrib:
                continue
            value = id_cell.find(q(MAIN_NS, "v"))
            if value is None or value.text is None:
                continue
            owner_id = value.text
            if owner_id not in expected_ids:
                continue
            metadata_index = int(image_cell.attrib["vm"]) - 1
            if metadata_index < 0 or metadata_index >= len(image_paths):
                raise ValueError(f"invalid rich-value metadata index for relic {owner_id}")
            data = archive.read(image_paths[metadata_index])
            relative_path = f"icons/relics/{owner_id}.png"
            destination = pack_root / Path(relative_path)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            result.append(asset(owner_id, "default", relative_path, data))
    result.sort(key=lambda item: int(str(item["ownerId"])))
    imported_ids = {str(item["ownerId"]) for item in result}
    if imported_ids != expected_ids or len(result) != len(expected_ids):
        missing = sorted(expected_ids - imported_ids, key=int)
        extra = sorted(imported_ids - expected_ids, key=int)
        raise ValueError(f"relic icon coverage mismatch; missing={missing}, extra={extra}")
    return result


def update_pack_manifest(pack_root: Path, icon_files: list[str], version: str | None) -> None:
    path = pack_root / "manifest.json"
    manifest = load_json(path)
    files = manifest.get("files")
    if not isinstance(files, list) or not all(isinstance(item, str) for item in files):
        raise ValueError("resource-pack manifest must contain a files list")
    base_files = [item for item in files if item != "icons.json" and not item.startswith("icons/")]
    manifest["files"] = base_files + ["icons.json"] + icon_files
    if version:
        manifest["version"] = version
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def reject_stale_icon_files(pack_root: Path, expected_files: set[str]) -> None:
    icon_root = pack_root / "icons"
    actual_files = {
        path.relative_to(pack_root).as_posix()
        for path in icon_root.rglob("*")
        if path.is_file()
    }
    stale = sorted(actual_files - expected_files)
    if stale:
        raise ValueError(f"stale files exist below the generated icon directory: {stale}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("character_catalog", type=Path)
    parser.add_argument("character_icon_source", type=Path)
    parser.add_argument("workbook", type=Path)
    parser.add_argument("relic_catalog", type=Path)
    parser.add_argument("pack_root", type=Path)
    parser.add_argument("--pack-version")
    args = parser.parse_args()

    character_catalog = load_json(args.character_catalog)
    relic_catalog = load_json(args.relic_catalog)
    character_icons, unmatched = copy_character_icons(
        character_catalog,
        args.character_icon_source,
        args.pack_root,
    )
    relic_icons = extract_relic_icons(relic_catalog, args.workbook, args.pack_root)
    document = {
        "schemaVersion": 1,
        "source": {
            "characters": "redive.estertion.win 角色图标的本地缓存；只导入可与角色目录 ID 对应的 PNG/WebP。",
            "relics": "共识 Excel 数据源的“迷宫遗物”工作表 B 列内嵌 PNG。",
            "note": "图标只作本地 UI 和视觉识别资源；攻略表不进入应用。",
        },
        "coverage": {
            "catalogCharacters": len(character_catalog["characters"]),
            "coveredCharacters": len({item["ownerId"] for item in character_icons}),
            "characterIconFiles": len(character_icons),
            "unmatchedCharacterSourceFiles": unmatched,
            "catalogRelics": len(relic_catalog["relics"]),
            "coveredRelics": len({item["ownerId"] for item in relic_icons}),
            "relicIconFiles": len(relic_icons),
        },
        "characterIcons": character_icons,
        "relicIcons": relic_icons,
    }
    icon_manifest = args.pack_root / "icons.json"
    icon_manifest.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    icon_files = [str(item["file"]) for item in character_icons + relic_icons]
    reject_stale_icon_files(args.pack_root, set(icon_files))
    update_pack_manifest(args.pack_root, icon_files, args.pack_version)
    print(
        f"wrote {len(character_icons)} character icons for {document['coverage']['coveredCharacters']} characters, "
        f"{len(relic_icons)} relic icons, and skipped {unmatched} unmatched character source files"
    )


if __name__ == "__main__":
    main()
