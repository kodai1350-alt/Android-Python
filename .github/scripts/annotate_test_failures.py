"""Turns failed JUnit tests into GitHub annotations, which anyone can read without signing in.

Usage: python3 annotate_test_failures.py <test-results dir> [label]
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
label = sys.argv[2] if len(sys.argv) > 2 else "Failed test"
for path in sorted(root.rglob("TEST-*.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        for problem in list(case.findall("failure")) + list(case.findall("error")):
            message = (problem.get("message") or problem.text or "").strip().splitlines()
            first = message[0][:300] if message else ""
            name = f"{case.get('classname')}.{case.get('name')}"
            # Annotation text must be one line; %0A is GitHub's escaped newline.
            detail = "%0A".join(line[:200] for line in (problem.text or "").strip().splitlines()[:8])
            print(f"::error title={label}::{name}: {first}%0A{detail}")
