#!/usr/bin/env python3
"""Small content guard; examines staged blobs without printing credentials."""

import argparse
from pathlib import Path
import re
import subprocess


PATTERNS = [
    re.compile(pattern) for pattern in (
        rb"sk-[A-Za-z0-9_-]{20,}",
        rb"AKIA[0-9A-Z]{16}",
        rb"gh[pousr]_[A-Za-z0-9]{20,}",
        rb"github_pat_[A-Za-z0-9_]{20,}",
        rb"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----",
    )
]


def git(*args):
    return subprocess.check_output(["git", *args])


def inspect_content(path, content):
    findings = []
    name = Path(path).name
    if (name == ".env" or name.startswith(".env.")) and name != ".env.example":
        findings.append(f"{path}: local environment file must not be committed")
    if name in {"application-local.yml", "application-local.yaml"}:
        findings.append(f"{path}: local application config must not be committed")
    if "harness-references" in Path(path).parts:
        findings.append(f"{path}: external reference checkout must not be committed")
    if any(pattern.search(content) for pattern in PATTERNS):
        findings.append(f"{path}: possible credential; replace with an environment variable")
    return findings


def check(staged=False):
    root = Path(git("rev-parse", "--show-toplevel").decode().strip())
    if staged:
        paths = git("diff", "--cached", "--name-only", "--diff-filter=ACMRT", "-z").split(b"\0")
    else:
        paths = git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0")
    findings = []
    for raw in sorted(set(paths) - {b""}):
        path = raw.decode("utf-8", errors="surrogateescape")
        if staged:
            content = git("show", f":{path}")
        else:
            file = root / path
            if not file.is_file() or file.is_symlink():
                continue
            content = file.read_bytes()
        findings.extend(inspect_content(path, content))
    whitespace = ["git", "diff", "--check"]
    if staged:
        whitespace.append("--cached")
    result = subprocess.run(whitespace, capture_output=True)
    if result.returncode:
        # git diff --check includes offending source lines, which may contain secrets.
        findings.append("Whitespace errors detected; inspect git diff --check locally")
    for finding in findings:
        print(finding)
    if not findings:
        print("Repository checks passed" + (" (staged content)" if staged else ""))
    return bool(findings)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true")
    raise SystemExit(check(parser.parse_args().staged))
