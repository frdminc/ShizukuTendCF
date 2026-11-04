#!/usr/bin/env python3
"""Usage: merge_translations.py <locale> <translations.json> [--lang "Name"]
Validates (placeholders/markup preserved) and appends to values-<locale>/strings_machine_translated.xml."""
import sys, json, os, re
from i18n_lib import RES, base_strings, placeholders, fix_escapes, locale_keys
loc, jf = sys.argv[1], sys.argv[2]
lang = sys.argv[sys.argv.index('--lang') + 1] if '--lang' in sys.argv else loc
tr = json.load(open(jf, encoding='utf-8'))
base = base_strings()
have = locale_keys(loc)
d = os.path.join(RES, 'values-' + loc); os.makedirs(d, exist_ok=True)
out = os.path.join(d, 'strings_machine_translated.xml')
existing = {}
if os.path.exists(out):
    from i18n_lib import parse
    existing = {k: v[0] for k, v in parse(out).items()}
bad, ok = [], 0
for k, v in tr.items():
    if k not in base or k in have: continue
    if not isinstance(v, str) or not v.strip(): bad.append((k, 'empty')); continue
    v = fix_escapes(v)
    if placeholders(v) != placeholders(base[k]): bad.append((k, 'placeholder/markup mismatch')); continue
    existing[k] = v; ok += 1
hdr = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  MACHINE-ASSISTED TRANSLATIONS ({lang}) - NEEDS NATIVE REVIEW.
  Drafted with AI assistance to fill missing strings. If you are a native speaker, please review
  and correct these, then move each corrected string into strings.xml (delete it here: a key
  must live in only one file). Thank you! See TRANSLATING.md.
-->
<resources>
'''
body = ''.join(f'    <string name="{k}">{v}</string>\n' for k, v in existing.items())
open(out, 'w', encoding='utf-8').write(hdr + body + '</resources>\n')
print(json.dumps({'locale': loc, 'merged': ok, 'rejected': bad[:15], 'rejected_count': len(bad), 'total_in_file': len(existing)}))
