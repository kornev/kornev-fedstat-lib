#!/usr/bin/env python3
"""Neutral <script> body extraction (html.parser, script = raw-text mode).

Splits dev/verify/out/page-live.html into out/scripts/script-NN.txt so the
R and Clojure harnesses parse byte-identical input slices.
"""
from html.parser import HTMLParser
from pathlib import Path

OUT = Path("dev/verify/out/scripts")
OUT.mkdir(parents=True, exist_ok=True)


class Extractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.in_script = False
        self.buf = []
        self.n = 0

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.in_script = True
            self.buf = []

    def handle_endtag(self, tag):
        if tag == "script" and self.in_script:
            (OUT / f"script-{self.n:02d}.txt").write_text("".join(self.buf), encoding="utf-8")
            self.n += 1
            self.in_script = False

    def handle_data(self, data):
        if self.in_script:
            self.buf.append(data)


html = Path("dev/verify/out/page-live.html").read_text(encoding="utf-8")
p = Extractor()
p.feed(html)
print(f"extracted {p.n} script bodies into {OUT}")
