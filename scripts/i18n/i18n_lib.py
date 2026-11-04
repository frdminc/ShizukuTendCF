"""Shared helpers for finding and merging missing Android string translations."""
import re, glob, os, json, sys

RES = os.path.join(os.path.dirname(__file__), '..', '..', 'manager', 'src', 'main', 'res')
STR = re.compile(r'<string\s+([^>]*?)>(.*?)</string>', re.S)
NAME = re.compile(r'name="([^"]+)"')
PH = re.compile(r'%(?:\d+\$)?[sdf]|\^\d|<[^>]+>|\\n|%%')

def parse(path):
    out = {}
    s = open(path, encoding='utf-8').read()
    s = re.sub(r'<!--.*?-->', '', s, flags=re.S)
    for attrs, body in STR.findall(s):
        m = NAME.search(attrs)
        if m:
            out[m.group(1)] = (body, 'translatable="false"' in attrs)
    return out

def base_strings():
    d = {}
    for f in sorted(glob.glob(os.path.join(RES, 'values', 'strings*.xml'))):
        for k, (v, nt) in parse(f).items():
            if not nt and not v.strip().startswith('@string/'):
                d[k] = v
    return d

def locale_keys(loc):
    keys = set()
    for f in glob.glob(os.path.join(RES, 'values-' + loc, '*.xml')):
        keys |= set(parse(f).keys())
    return keys

def locales():
    return sorted(os.path.basename(p)[7:] for p in glob.glob(os.path.join(RES, 'values-*')) if re.fullmatch(r'values-[a-z]{2,3}(-r[A-Z]{2})?', os.path.basename(p)))

def missing(loc):
    base = base_strings()
    have = locale_keys(loc)
    return {k: v for k, v in base.items() if k not in have}

CDATA = re.compile(r'<!\[CDATA\[(.*?)\]\]>', re.S)

def placeholders(s):
    # Compare what is inside a CDATA wrapper; the wrapper itself would match PH as one big "tag".
    return sorted(PH.findall(CDATA.sub(lambda m: m.group(1), s)) + ['CDATA'] * len(CDATA.findall(s)))

def fix_escapes(v):
    # A doubled backslash before an escape (s\\'il) is a literal backslash plus a bare quote to aapt.
    v = re.sub(r"\\\\(['\"n])", r"\\\1", v)
    if v.lstrip().startswith('<![CDATA['):
        # aapt still processes escapes inside CDATA: apostrophes must be backslash-escaped.
        return re.sub(r"(?<!\\)'", r"\\'", v)
    if v.strip().startswith('"') and v.strip().endswith('"'):
        return v
    v = re.sub(r"(?<!\\)'", r"\\'", v)
    v = re.sub(r'&(?!(?:amp|lt|gt|quot|apos|#\d+);)', '&amp;', v)
    return v
