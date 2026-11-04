#!/usr/bin/env python3
"""Fill missing translations locally with the Gemini CLI (`agyp`) - no Claude tokens.
Usage: local_translate.py <locale> "<Language name>" [chunk=40]
Resumable: re-run any time; only still-missing keys are translated. Output is validated and
merged by merge_translations.py (placeholder/markup mismatches are rejected)."""
import sys, json, subprocess, tempfile, os, re, time
from i18n_lib import missing
loc, lang = sys.argv[1], sys.argv[2]
chunk = int(sys.argv[3]) if len(sys.argv) > 3 else 40
PROMPT = ("Translate the JSON values from English to %s for an Android app UI (Shizuku+, a Shizuku manager). "
          "Keep the keys. Keep EXACT placeholders (%%1$s, %%d, ^1), HTML tags, CDATA wrappers, \\n and \\' escapes, "
          "and leave brand/technical terms (Shizuku, Shizuku+, ADB, Wi-Fi, TCP, root, rish, Sentry, GitHub) untranslated. "
          "Output ONLY the JSON object.")
skip = 0
while True:
    items = list(missing(loc).items())[skip:skip + chunk]
    if not items:
        print('done', loc); break
    payload = json.dumps(dict(items), ensure_ascii=False)
    for attempt in range(3):
        r = subprocess.run(['agyp', PROMPT % lang], input=payload, capture_output=True, text=True)
        out = r.stdout
        if 'RESOURCE_EXHAUSTED' in (out + r.stderr):
            print('Gemini quota exhausted - re-run later (progress is saved).'); sys.exit(2)
        m = re.search(r'\{.*\}', out, re.S)
        try:
            tr = json.loads(m.group(0)); break
        except Exception:
            tr = None; time.sleep(3)
    if tr is None:
        skip += chunk; continue
    with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False, encoding='utf-8') as f:
        json.dump(tr, f, ensure_ascii=False)
    res = subprocess.run([sys.executable, 'merge_translations.py', loc, f.name, '--lang', lang], capture_output=True, text=True)
    os.unlink(f.name)
    info = json.loads(res.stdout.strip().splitlines()[-1])
    print(loc, 'merged', info['merged'], 'rejected', info['rejected_count'], flush=True)
    skip += info['rejected_count']  # don't loop forever on strings that keep failing validation
