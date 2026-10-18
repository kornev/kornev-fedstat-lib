#!/usr/bin/env python3
"""One-off fixture capture for fedstat-lib tests.

Reimplements just enough of the fedstatAPIr (R) flow to record raw
fedstat.ru responses as test fixtures:

  test/fixtures/page-31074.html   GET  /indicator/31074
  test/fixtures/sdmx-31074.xml    POST /indicator/downloadData.do?format=sdmx
  test/fixtures/orgs.html         GET  /organizations/

The SDMX POST repeats the R package's small README query
(Primorsky krai, January 2023, two "Виды показателя", all goods).

Requires network access to fedstat.ru. Safe to re-run: fixtures are
overwritten with fresh captures.
"""
import json
import re
import subprocess
import sys
import tempfile
import urllib.parse
from pathlib import Path

BASE = "https://www.fedstat.ru"
INDICATOR_ID = "31074"
FIXTURES = Path(__file__).resolve().parent.parent / "test" / "fixtures"

HEADERS = [
    # Browser-like UA: EMISS anti-bot blocks non-standard user agents
    # (see fedstatAPIr README, "Ошибка 403 (Forbidden)").
    "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H", "Accept-Language: ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
]

QUOTE_HACK = re.compile(r"\b(?=([^']*'[^']*')*[^']*$)")


def urlencode_r_reserved(s: str) -> str:
    """R utils::URLencode(s, reserved=TRUE): keep [A-Za-z0-9_.~-], %20 for space."""
    out = []
    for b in s.encode("utf-8"):
        ch = chr(b)
        if ch.isalnum() or ch in "_.~-":
            out.append(ch)
        else:
            out.append("%%%02X" % b)
    return "".join(out)


def js_fragment_to_obj(lines, start_marker, end_marker, from_line, to_line_offset):
    """Port of parse_js1/parse_js2 shared trick: slice script lines, quote
    unquoted keys via word-boundary lookahead, parse as JSON."""
    start = next(i for i, l in enumerate(lines) if start_marker in l)
    end = next(i for i, l in enumerate(lines) if end_marker in l)
    fragment = lines[from_line(start):to_line_offset(end)]
    text = "\n".join(fragment)
    text = QUOTE_HACK.sub("'", text)
    text = text.replace("'", '"')
    return json.loads("{" + text + "}")


def parse_filters_js(lines):
    # R: script[seq(grep("filters: \\{")+1, grep("left_columns: \\[")-2)]
    return js_fragment_to_obj(lines, "filters: {", "left_columns: [",
                              lambda i: i + 1, lambda i: i - 1)


def parse_object_ids_js(lines):
    # R: script[seq(grep("left_columns: \\["), grep("grid.init();")-2)]
    # 1-based end g-2 == 0-based slice end g-1 (exclusive)
    return js_fragment_to_obj(lines, "left_columns: [", "grid.init();",
                              lambda i: i, lambda i: i - 1)


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip()).lower()


def main():
    FIXTURES.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="fedstat-capture-"))
    jar = tmp / "cookies.txt"
    hdr_file = tmp / "headers.txt"

    def curl(args):
        res = subprocess.run(["curl", "-sS", "--max-time", "180",
                              "-c", str(jar), "-b", str(jar),
                              "-D", str(hdr_file), *HEADERS, *args],
                             capture_output=True)
        if res.returncode != 0:
            sys.exit(f"curl failed ({res.returncode}): {res.stderr.decode()}")

    def get(url, out):
        curl(["-o", str(out), url])
        headers = hdr_file.read_text(errors="replace")
        status = re.search(r"^HTTP/\S+\s+(\d+)", headers, re.MULTILINE)
        return int(status.group(1)) if status else 0

    def post(url, body):
        body_file = tmp / "body.txt"
        body_file.write_text(body, encoding="utf-8")
        curl(["-H", "Content-Type: application/x-www-form-urlencoded",
              "--data-binary", f"@{body_file}", "-o", str(tmp / "post-body"),
              url])
        headers = hdr_file.read_text(errors="replace")
        status = re.search(r"^HTTP/\S+\s+(\d+)", headers, re.MULTILINE)
        ctype = re.search(r"^content-type:\s*(\S+)", headers,
                          re.MULTILINE | re.IGNORECASE)
        return (int(status.group(1)) if status else 0,
                ctype.group(1) if ctype else "",
                (tmp / "post-body").read_bytes())

    print(f"GET {BASE}/indicator/{INDICATOR_ID} ...")
    page_file = FIXTURES / "page-31074.html"
    status = get(f"{BASE}/indicator/{INDICATOR_ID}", page_file)
    page = page_file.read_bytes()
    print(f"  status={status} saved page-31074.html ({len(page)} bytes)")
    if status != 200:
        sys.exit(f"GET indicator page failed: HTTP {status}")

    html = page.decode("utf-8", errors="replace")

    # --- CSRF token from downloadTokenHolder ---
    holder = re.search(r"<div[^>]*id=\"downloadTokenHolder\"[^>]*>(.*?)</div>",
                       html, re.DOTALL)
    if not holder:
        sys.exit("downloadTokenHolder not found on indicator page")
    name_m = re.search(r"<input[^>]*name=\"struts\.token\.name\"[^>]*value=\"([^\"]*)\"",
                       holder.group(1))
    if not name_m:
        sys.exit("struts.token.name input not found")
    token_name = name_m.group(1)
    token_m = re.search(r"<input[^>]*name=\"" + re.escape(token_name) +
                        r"\"[^>]*value=\"([^\"]*)\"", holder.group(1))
    if not token_m:
        sys.exit("CSRF token value input not found")
    token = token_m.group(1)
    print(f"  CSRF: name={token_name!r} token={token[:12]}...")

    # --- inline scripts ---
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", html,
                         re.DOTALL | re.IGNORECASE)
    idx = next(i for i, s in enumerate(scripts) if "filters: {" in s)
    print(f"  filters script is #{idx + 1} of {len(scripts)} script nodes")
    if idx != 11:
        print("  WARNING: R package expects this to be script #12")
    script_lines = scripts[idx].split("\n")

    filters = parse_filters_js(script_lines)
    raw_objects = parse_object_ids_js(script_lines)

    rename = {"left_columns": "lineObjectIds", "top_columns": "columnObjectIds",
              "groups": "lineObjectIds", "filterObjectIds": "lineObjectIds"}
    objects = {rename.get(k, k): v for k, v in raw_objects.items()}
    flat_ids = [str(x) for v in objects.values() for x in v]
    if "0" not in flat_ids:
        objects.setdefault("filterObjectIds", []).append("0")

    object_class = {}
    for cls, ids in objects.items():
        for fid in ids:
            object_class.setdefault(str(fid), cls)

    # rows: filter_field_id, filter_field_title, filter_value_id, filter_value_title
    rows = []
    for fid, f in filters.items():
        values = f.get("values") or {}
        if not values:
            sys.exit(f"no values in filter {f.get('title')!r}")
        for vid, v in values.items():
            rows.append({
                "filter_field_id": str(fid),
                "filter_field_title": f["title"],
                "filter_value_id": str(vid),
                "filter_value_title": v["title"].replace("&quot;", '"'),
                "filter_field_object_ids": object_class.get(str(fid), "lineObjectIds"),
            })

    indicator_rows = [r for r in rows if r["filter_field_id"] == "0"]
    assert indicator_rows, "indicator row (field id 0) not found"
    indicator_id = indicator_rows[0]["filter_value_id"]
    indicator_title = indicator_rows[0]["filter_value_title"]

    # --- select the small README filter set ---
    # Exact normalized filter_field_title matching (R semantics), keys are
    # norm("Классификатор объектов административно-территориального деления (ОКАТО)") etc.
    user_filters = {
        "классификатор объектов административно-территориального деления (окато)":
            ["Приморский край"],
        "виды показателя": ["К соответствующему периоду предыдущего года",
                            "К предыдущему месяцу"],
        "период": ["Январь"],
        "год": ["2023"],
        "виды товаров и услуг": ["*"],
    }

    by_field = {}
    for r in rows:
        by_field.setdefault(r["filter_field_id"], []).append(r)

    selected = []  # (field_id, value_id) pairs
    for fid, frs in by_field.items():
        title_n = norm(frs[0]["filter_field_title"])
        wanted = user_filters.get(title_n)
        if wanted is None:
            wanted = ["*"]  # auto single-value + Pokazatel
        if "*" in wanted and len(wanted) > 1:
            sys.exit(f"'*' mixed with values for {frs[0]['filter_field_title']!r}")
        if wanted == ["*"]:
            selected.extend((fid, r["filter_value_id"]) for r in frs)
        else:
            by_val = {norm(r["filter_value_title"]): r for r in frs}
            for w in wanted:
                if norm(w) not in by_val:
                    sys.exit(f"value {w!r} not found in field {frs[0]['filter_field_title']!r}")
                selected.append((fid, by_val[norm(w)]["filter_value_id"]))

    body_parts = [
        "title=" + urlencode_r_reserved(indicator_title),
        "struts.token.name=" + token_name,
        token_name + "=" + token,
        "id=" + indicator_id,
    ]
    seen = set()
    for r in rows:
        key = (r["filter_field_id"], r["filter_field_object_ids"])
        if key in seen:
            continue
        seen.add(key)
        param = {"lineObjectIds": "lineObjectIds",
                 "columnObjectIds": "columnObjectIds",
                 "filterObjectIds": "filterObjectIds"}[r["filter_field_object_ids"]]
        body_parts.append(f"{param}={r['filter_field_id']}")
    for fid, vid in selected:
        body_parts.append(f"selectedFilterIds={fid}_{vid}")

    body = "&".join(body_parts)
    print(f"POST body: {len(body)} chars, "
          f"{len([p for p in body_parts if p.startswith('selectedFilterIds')])} selected ids")

    status, ctype, sdmx = post(
        f"{BASE}/indicator/downloadData.do?format=sdmx", body)
    print(f"  status={status} content-type={ctype!r} bytes={len(sdmx)}")
    if status != 200 or "xml" not in ctype.lower():
        sys.exit(f"unexpected POST response: status={status} ctype={ctype}")
    (FIXTURES / "sdmx-31074.xml").write_bytes(sdmx)
    print("  saved sdmx-31074.xml")

    print(f"GET {BASE}/organizations/ ...")
    orgs_file = FIXTURES / "orgs.html"
    status = get(f"{BASE}/organizations/", orgs_file)
    orgs = orgs_file.read_bytes()
    print(f"  status={status} saved orgs.html ({len(orgs)} bytes)")
    if status != 200:
        sys.exit(f"GET organizations failed: HTTP {status}")

    print("done")


if __name__ == "__main__":
    main()
