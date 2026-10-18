#!/usr/bin/env python3
"""Validate a read-only frontend snapshot of the index, including staged configs."""

from pathlib import Path
import subprocess
import tempfile


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def snapshot(root, target):
    entries = git(root, "ls-files", "--stage", "-z", "--", "frontend").split(b"\0")
    for entry in filter(None, entries):
        metadata, raw_path = entry.split(b"\t", 1)
        mode, oid, stage = metadata.split()
        path = Path(raw_path.decode("utf-8", errors="surrogateescape")).relative_to("frontend")
        if stage != b"0":
            raise ValueError("Resolve frontend merge conflicts before committing")
        if mode not in (b"100644", b"100755"):
            raise ValueError(f"Frontend snapshot requires a regular file: {path}")
        file = target / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(git(root, "cat-file", "blob", oid.decode("ascii")))


def validate(target, installed):
    subprocess.run([str(installed / ".bin" / "prettier"), "--check", "."], cwd=target, check=True)
    subprocess.run([str(installed / ".bin" / "oxlint"), "--deny-warnings",
                    "--report-unused-disable-directives"], cwd=target, check=True)


def check(root):
    changed = git(root, "diff", "--cached", "--name-only", "-z", "--", "frontend")
    if not changed:
        return
    installed = root / "frontend" / "node_modules"
    if not (installed / ".bin" / "prettier").is_file() or not (installed / ".bin" / "oxlint").is_file():
        raise ValueError("Install frontend dependencies first: cd frontend && bun install --frozen-lockfile")
    with tempfile.TemporaryDirectory(prefix="loopcraft-frontend-index-") as directory:
        target = Path(directory)
        snapshot(root, target)
        if not any(target.iterdir()):
            return
        # Only dependencies come from the worktree. Source and configuration come from Git blobs.
        (target / "node_modules").symlink_to(installed.resolve(), target_is_directory=True)
        validate(target, installed)


if __name__ == "__main__":
    try:
        root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
        check(root)
    except (ValueError, subprocess.CalledProcessError) as error:
        # Avoid echoing source blobs or command input on a failed check.
        print(str(error) if isinstance(error, ValueError) else "Staged frontend checks failed")
        raise SystemExit(1)
