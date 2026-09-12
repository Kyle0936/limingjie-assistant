#!/usr/bin/env python3
"""Convert the approved LandosolRoster name tables into resource-pack JSON.

Only names, aliases, IDs, and availability are imported.  Gameplay roles,
attack types, and scores intentionally remain neutral placeholders for manual
review before they can influence a route decision.
"""

from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path
from typing import Any


def _tables(source: Path) -> tuple[dict[int, list[Any]], set[int]]:
    tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
    values: dict[str, Any] = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in {"CHARA_NAME", "UnavailableChara"}:
                    values[target.id] = ast.literal_eval(node.value)
    names = values.get("CHARA_NAME")
    unavailable = values.get("UnavailableChara")
    if not isinstance(names, dict) or not isinstance(unavailable, set):
        raise ValueError("source must define CHARA_NAME and UnavailableChara")
    return names, unavailable


def convert(source: Path) -> dict[str, Any]:
    names, unavailable = _tables(source)
    characters: list[dict[str, Any]] = []
    for raw_id, raw_names in sorted(names.items(), key=lambda item: int(item[0])):
        if not isinstance(raw_names, list) or not raw_names or not all(isinstance(value, str) for value in raw_names):
            raise ValueError(f"CHARA_NAME[{raw_id}] must be a non-empty string list")
        canonical = raw_names[0].strip()
        aliases: list[str] = []
        normalized_aliases: set[str] = set()
        normalized_canonical = canonical.casefold()
        for alias in raw_names[1:]:
            alias = alias.strip()
            normalized_alias = alias.casefold()
            if alias and normalized_alias != normalized_canonical and normalized_alias not in normalized_aliases:
                aliases.append(alias)
                normalized_aliases.add(normalized_alias)
        characters.append(
            {
                "id": str(raw_id),
                "name": canonical,
                "officialName": "",
                "aliases": aliases,
                "available": int(raw_id) not in unavailable,
                "tags": [],
                "rarity": 0,
                "role": "",
                "attackType": "",
                "score": 0,
                "notes": "名称来自 LandosolRoster；黎明界决策字段待补充。",
                "references": {},
            }
        )
    return {"schemaVersion": 1, "characters": characters}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="path to LandosolRoster _pcr_data.py")
    parser.add_argument("output", type=Path, help="characters JSON output path")
    args = parser.parse_args()
    document = convert(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(document['characters'])} characters to {args.output}")


if __name__ == "__main__":
    main()
