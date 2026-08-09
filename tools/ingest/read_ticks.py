#!/usr/bin/env python3
"""Read a collected corpus back.

Each session file is a run of concatenated gzip members, one per uploaded batch, so a plain
``gzip.open`` walks the whole session. Within it, a line whose ``t`` is ``header`` re-establishes
the schema and the string dictionary for every row that follows — batches each carry their own
dictionary, so the reader tracks the current one as it goes rather than assuming a single table.

    python3 read_ticks.py ~/nova-data                    # summarise the corpus
    python3 read_ticks.py ~/nova-data --csv out.csv      # flatten local-player fields
    python3 read_ticks.py ~/nova-data --human-only       # drop module-driven ticks
"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Iterator


def read_session(path: Path, human_only: bool = False) -> Iterator[dict]:
    """Yield one dict per tick, with dictionary indices already resolved to strings."""
    fields: list[str] = []
    entity_fields: list[str] = []
    dictionary: list[str] = []
    meta: dict = {}

    with gzip.open(path, "rt", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                print(f"{path.name}:{line_number}: unreadable line, skipped", file=sys.stderr)
                continue

            if record.get("t") == "header":
                fields = record["fields"]
                entity_fields = record["entity_fields"]
                dictionary = record.get("dict", [])
                meta = {k: v for k, v in record.items()
                        if k not in ("t", "fields", "entity_fields", "dict")}
                continue

            if not fields:
                continue  # rows before the first header cannot be decoded

            row = dict(zip(fields, record["f"]))
            # String-valued columns arrive as dictionary indices; put the strings back.
            for column in ("main_item", "off_item", "dim", "block_below", "block_feet",
                           "block_head", "activity"):
                index = row.get(column)
                if isinstance(index, int) and 0 <= index < len(dictionary):
                    row[column] = dictionary[index]

            if human_only and row.get("overridden"):
                continue

            row["_session"] = meta.get("session")
            row["_pseudonym"] = meta.get("pseudonym")
            row["_schema"] = meta.get("schema")
            row["_events"] = [dictionary[i] for i in record.get("v", []) if i < len(dictionary)]
            row["_entities"] = [
                {**dict(zip(entity_fields, entity)),
                 "type": dictionary[entity[0]] if entity and entity[0] < len(dictionary) else None}
                for entity in record.get("e", [])
            ]
            yield row


def main() -> int:
    parser = argparse.ArgumentParser(description="Read a NovaClient contribution corpus")
    parser.add_argument("root", help="data root written by nova_ingest.py")
    parser.add_argument("--csv", help="flatten local-player fields to this CSV")
    parser.add_argument("--human-only", action="store_true",
                        help="drop ticks where a module overrode the player's input")
    parser.add_argument("--activity", help="keep only this activity (combat, traveling, mining, …)")
    args = parser.parse_args()

    root = Path(args.root).expanduser()
    sessions = sorted(root.rglob("*.ndjson.gz"))
    if not sessions:
        print(f"no sessions under {root}", file=sys.stderr)
        return 1

    ticks = 0
    overridden = 0
    contributors: set[str] = set()
    events: Counter = Counter()
    activities: Counter = Counter()
    segments: set[tuple] = set()
    writer = None
    handle = None

    try:
        for session in sessions:
            for row in read_session(session, human_only=args.human_only):
                if args.activity and row.get("activity") != args.activity:
                    continue
                ticks += 1
                overridden += 1 if row.get("overridden") else 0
                if row.get("_pseudonym"):
                    contributors.add(row["_pseudonym"])
                events.update(row["_events"])
                if row.get("activity"):
                    activities[row["activity"]] += 1
                    segments.add((row.get("_session"), row.get("segment")))

                if args.csv:
                    flat = {k: v for k, v in row.items() if not k.startswith("_")}
                    flat["events"] = "|".join(row["_events"])
                    flat["entities"] = len(row["_entities"])
                    if writer is None:
                        handle = open(args.csv, "w", newline="", encoding="utf-8")
                        writer = csv.DictWriter(handle, fieldnames=list(flat))
                        writer.writeheader()
                    writer.writerow(flat)
    finally:
        if handle:
            handle.close()

    hours = ticks / 20.0 / 3600.0
    print(f"sessions      {len(sessions)}")
    print(f"contributors  {len(contributors)}")
    print(f"ticks         {ticks:,}  (~{hours:.1f} h of play)")
    if not args.human_only:
        share = 100.0 * overridden / ticks if ticks else 0.0
        print(f"module-driven {overridden:,} ticks ({share:.1f}%) — filter with --human-only")
    if segments:
        print(f"segments      {len(segments):,}")
    if activities:
        print("activity      " + ", ".join(
            f"{name}={count:,} ({100.0 * count / ticks:.0f}%)"
            for name, count in activities.most_common()))
    if events:
        print("events        " + ", ".join(f"{name}={count:,}" for name, count in events.most_common()))
    if args.csv:
        print(f"wrote         {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
