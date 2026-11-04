#!/usr/bin/env python3
"""Usage: missing_keys.py <locale> [offset limit] -> JSON {key: english_raw_xml_text}"""
import sys, json
from i18n_lib import missing
loc = sys.argv[1]
m = missing(loc)
items = list(m.items())
if len(sys.argv) >= 4:
    o, n = int(sys.argv[2]), int(sys.argv[3]); items = items[o:o+n]
print(json.dumps(dict(items), ensure_ascii=False, indent=0))
