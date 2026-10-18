#!/usr/bin/env python3
"""Order-independent TSV comparison: rows are parsed and compared as
multisets of tuples (sorting bytewise inside python), so differences in
column order / row order between the two harnesses don't matter."""
import sys


def load(path):
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        rows = [tuple(line.rstrip("\n").split("\t")) for line in f if line.strip("\n")]
    return header, rows


a_path, b_path = sys.argv[1], sys.argv[2]
ha, ra = load(a_path)
hb, rb = load(b_path)

if set(ha) != set(hb):
    print(f"COLUMN MISMATCH\n  only {a_path}: {sorted(set(ha) - set(hb))}\n  only {b_path}: {sorted(set(hb) - set(ha))}")
    sys.exit(1)

# align b's columns to a's order
idx = [hb.index(c) for c in ha]
rb_aligned = [tuple(row[i] for i in idx) for row in rb]

sa, sb = sorted(ra), sorted(rb_aligned)
if sa == sb:
    print(f"IDENTICAL ({len(ra)} rows, {len(ha)} cols)")
    sys.exit(0)

only_a = [r for r in sa if r not in sb]
only_b = [r for r in sb if r not in sa]
print(f"ROW MISMATCH: {len(ra)} vs {len(rb)} rows, {len(only_a)} vs {len(only_b)} unique")
for r in only_a[:5]:
    print("  R :", "\t".join(r)[:160])
for r in only_b[:5]:
    print("  bb:", "\t".join(r)[:160])
sys.exit(1)
