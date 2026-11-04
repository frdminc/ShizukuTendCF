#!/usr/bin/env python3
"""Catch translation mistakes that break settings at runtime (not just wording).

1. Value arrays (*_value / *_values) must never be translated: they hold internal keys the
   code compares against ("open_app", "stable", ...). A translated copy silently breaks the
   setting for that language.
2. Label arrays must have the same number of items as English, or labels and values drift.

Usage: python3 scripts/i18n/check_arrays.py [res_dir ...]   (exit 1 on problems)
"""
import glob
import os
import re
import sys

ARR = re.compile(r'<(?:string-array|integer-array|array)\s+name="([^"]+)"[^>]*>(.*?)</(?:string-array|integer-array|array)>', re.S)


def arrays(text):
    return {m.group(1): len(re.findall(r"<item", m.group(2))) for m in ARR.finditer(text)}


def check(res):
    base = {}
    for f in glob.glob(os.path.join(res, "values", "*.xml")):
        base.update(arrays(open(f, encoding="utf-8").read()))
    problems = []
    for f in sorted(glob.glob(os.path.join(res, "values-*", "*.xml"))):
        for name, count in arrays(open(f, encoding="utf-8").read()).items():
            if name not in base:
                continue
            if name.endswith(("_value", "_values")):
                problems.append(f"{f}: '{name}' is a value array and must not be translated")
            elif count != base[name]:
                problems.append(f"{f}: '{name}' has {count} items, English has {base[name]}")
    return problems


if __name__ == "__main__":
    dirs = sys.argv[1:] or ["manager/src/main/res", "core/ui/src/main/res"]
    found = [p for d in dirs if os.path.isdir(d) for p in check(d)]
    print("\n".join(found) if found else "arrays OK")
    sys.exit(1 if found else 0)
