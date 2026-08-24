#!/usr/bin/env python3
"""Fail the build when the instrumented run reported zero tests.

An emulator job is expensive and, unlike the unit-test jobs, it can go green
while proving nothing: AGP skips `<device>DebugAndroidTest` silently when the
`androidTest` source set holds no tests, so deleting the last instrumented
test — or moving it into the wrong source directory, which is a one-character
mistake between `src/test` and `src/androidTest` — leaves a passing job that
boots an emulator and measures nothing.

That is the exact shape of the defect this whole tier exists to catch, so it
gets a guard rather than a convention. Sums the `tests` attribute across every
JUnit XML the run produced and exits non-zero on a total of zero, or on no XML
at all.

Skips are subtracted rather than counted. An `@Ignore`d suite reports
`tests="7" skipped="7"` and would otherwise print `OK: 7 instrumented test(s)`
while executing none — the same expensive-green-light this guard exists to
prevent, reached by a different door than the empty source set.

Deliberately does NOT check failure counts: Gradle already fails the build on a
failing test, and duplicating that here would make the two disagree the first
time one of them changes.

**Reads whatever XML is on disk, with no freshness check.** In CI that is
exactly the run that just happened, because the workspace is a fresh checkout.
Locally it is not: a stale `androidTest-results/` from an earlier run satisfies
this guard, so a local green means something only on a clean tree or
immediately after the Gradle task in the same invocation.

Usage: python3 .github/scripts/assert_instrumented_tests_ran.py [results-dir]
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_RESULTS_DIR = Path("app/build/outputs/androidTest-results")


def main() -> int:
    results_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_RESULTS_DIR

    if not results_dir.is_dir():
        print(f"FAIL: no instrumented results directory at {results_dir}", file=sys.stderr)
        return 1

    xml_files = sorted(results_dir.rglob("*.xml"))
    if not xml_files:
        print(f"FAIL: no JUnit XML under {results_dir}", file=sys.stderr)
        return 1

    total = 0
    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError as exc:
            print(f"FAIL: could not parse {xml_file}: {exc}", file=sys.stderr)
            return 1

        # AGP writes one <testsuite> per file, but a <testsuites> wrapper is
        # valid JUnit XML and costs nothing to accept.
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            declared = int(suite.get("tests", "0"))
            skipped = int(suite.get("skipped", "0"))
            executed = declared - skipped
            total += executed
            detail = f" ({skipped} skipped)" if skipped else ""
            print(f"  {xml_file.name}: {executed} test(s) executed{detail}")

    if total == 0:
        print(
            f"FAIL: the instrumented run executed 0 tests across {len(xml_files)} result file(s).\n"
            "      An emulator booted and measured nothing. Check that the tests are in\n"
            "      app/src/androidTest/ and not app/src/test/, and that they are not all @Ignore'd.",
            file=sys.stderr,
        )
        return 1

    print(f"OK: {total} instrumented test(s) executed across {len(xml_files)} result file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
