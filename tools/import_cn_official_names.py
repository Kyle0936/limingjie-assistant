#!/usr/bin/env python3
"""Enrich a LandosolRoster character JSON with official CN display names.

The CN database stores unit IDs as ``<roster character id><variant>``.  This
tool reads only the lightweight ``unit_data`` name table from the downloaded
SQLite release and writes the selected name into ``officialName``.  The full
SQLite database is never copied into the APK.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path
from typing import Any


def _load_official_names(database: Path) -> dict[str, str]:
    connection = sqlite3.connect(str(database))
    try:
        columns = {row[1] for row in connection.execute("PRAGMA table_info(unit_data)")}
        if not {"unit_id", "unit_name"}.issubset(columns):
            raise ValueError("database unit_data table must contain unit_id and unit_name")
        rows = connection.execute(
            "SELECT unit_id, unit_name FROM unit_data "
            "WHERE unit_name IS NOT NULL AND TRIM(unit_name) <> '' "
            "ORDER BY unit_id"
        ).fetchall()
    finally:
        connection.close()

    candidates: dict[str, list[tuple[int, str]]] = {}
    for raw_id, raw_name in rows:
        try:
            unit_id = int(raw_id)
        except (TypeError, ValueError) as error:
            raise ValueError(f"unit_data.unit_id is not numeric: {raw_id!r}") from error
        name = str(raw_name).strip()
        if not name:
            continue
        candidates.setdefault(str(unit_id // 100), []).append((unit_id, name))

    # Most characters have one row.  For NPC/alternate rows, prefer the
    # standard ``01`` variant and then the lowest unit ID for determinism.
    official: dict[str, str] = {}
    for character_id, values in candidates.items():
        unit_id, name = min(values, key=lambda item: (item[0] % 100 != 1, item[0]))
        del unit_id
        official[character_id] = name
    return official


def _normalize(value: str) -> str:
    return value.strip().casefold()


def _safe_official_name(character: dict[str, Any], imported_name: str) -> str:
    """Avoid replacing a distinct canonical roster identity with a reused DB battle name.

    Some NPC/special unit rows reuse another character's unit_data display name.  The roster
    canonical name is authoritative for identity in that case; otherwise exact-name lookup can
    become ambiguous (for example 1914 豪绅 previously imported officialName=可可萝).
    """
    canonical = str(character.get("name", "")).strip()
    if not imported_name or not canonical:
        return imported_name
    if _normalize(imported_name) == _normalize(canonical):
        return imported_name
    aliases = character.get("aliases", [])
    known_names = {
        _normalize(value)
        for value in [canonical, *aliases]
        if isinstance(value, str) and value.strip()
    }
    return imported_name if _normalize(imported_name) in known_names else canonical


def enrich(roster: Path, database: Path) -> tuple[dict[str, Any], int]:
    document = json.loads(roster.read_text(encoding="utf-8"))
    characters = document.get("characters")
    if not isinstance(characters, list):
        raise ValueError("roster JSON must contain a characters list")

    official_names = _load_official_names(database)
    matched = 0
    for character in characters:
        if not isinstance(character, dict):
            raise ValueError("each character must be a JSON object")
        character_id = str(character.get("id", ""))
        official_name = _safe_official_name(character, official_names.get(character_id, ""))
        if official_name:
            matched += 1

        # Keep the old LandosolRoster canonical name as a compatibility alias,
        # but do not store an exact duplicate of the new official name.
        aliases = character.get("aliases", [])
        if not isinstance(aliases, list):
            raise ValueError(f"character {character_id} aliases must be a list")
        character["aliases"] = [
            alias for alias in aliases
            if isinstance(alias, str) and _normalize(alias) != _normalize(official_name)
        ]

        ordered: dict[str, Any] = {}
        for key, value in character.items():
            if key == "officialName":
                continue
            ordered[key] = value
            if key == "name":
                ordered["officialName"] = official_name
        if "officialName" not in ordered:
            ordered["officialName"] = official_name
        character.clear()
        character.update(ordered)
    return document, matched


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path, help="downloaded CN priconne-database SQLite file")
    parser.add_argument("roster", type=Path, help="characters.landosolroster.json to enrich")
    parser.add_argument("output", type=Path, help="enriched JSON output path")
    args = parser.parse_args()

    document, matched = enrich(args.roster, args.database)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(document['characters'])} characters; matched {matched} official CN names")


if __name__ == "__main__":
    main()
