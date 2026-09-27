"""Turn the JUnit XML Gradle leaves behind into something a CI log will say.

Gradle's test task prints nothing on success, so a green run is no evidence
that any test ran - a suite reduced to zero tests passes exactly as loudly as
one that works. This reads the results and says how many there were, and fails
when the answer is none.

Writes to the step summary as well as stdout, so the numbers are on the run's
front page rather than buried in a log nobody opens.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

RESULTS = "app/build/test-results/**/*.xml"


def main() -> int:
    files = sorted(glob.glob(RESULTS, recursive=True))
    if not files:
        print("::error::No test result XML under app/build/test-results - "
              "the test task did not run")
        return 1

    suites = []
    totals = {"tests": 0, "failed": 0, "skipped": 0, "time": 0.0}

    for path in files:
        root = ET.parse(path).getroot()
        if root.tag != "testsuite":
            continue
        tests = int(root.get("tests", 0))
        failed = int(root.get("failures", 0)) + int(root.get("errors", 0))
        skipped = int(root.get("skipped", 0))
        suites.append((root.get("name", "?"), tests, failed, skipped))
        totals["tests"] += tests
        totals["failed"] += failed
        totals["skipped"] += skipped
        totals["time"] += float(root.get("time", 0) or 0)

    if totals["tests"] == 0:
        print("::error::Test result XML exists but contains no tests")
        return 1

    headline = (
        "%d tests in %d classes, %d failed, %d skipped, %.1fs"
        % (totals["tests"], len(suites), totals["failed"], totals["skipped"],
           totals["time"])
    )
    print(headline)
    for name, tests, failed, skipped in suites:
        note = ""
        if failed:
            note += "  %d FAILED" % failed
        if skipped:
            note += "  %d skipped" % skipped
        print("  %-4d %s%s" % (tests, name, note))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("## Tests\n\n**%s**\n\n" % headline)
            out.write("| tests | class |\n|---:|---|\n")
            for name, tests, failed, skipped in suites:
                flag = " ❌" if failed else ""
                out.write("| %d | `%s`%s |\n" % (tests, name, flag))

    return 1 if totals["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
