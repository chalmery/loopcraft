"""Git fixtures verify that hook validation cannot substitute unstaged files."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_frontend_staged as frontend


class StagedFrontendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.source = self.root / "frontend" / "src" / "example.tsx"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("export const value = 1\n")
        self.config = self.root / "frontend" / ".prettierrc.json"
        self.config.write_text('{"semi": false}\n')
        self.git("add", "frontend")
        for tool in ("prettier", "oxlint"):
            executable = self.root / "frontend" / "node_modules" / ".bin" / tool
            executable.parent.mkdir(parents=True, exist_ok=True)
            executable.write_text("fixture")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args])

    def test_partial_staging_uses_staged_source_and_config_without_changing_index(self):
        before = self.git("ls-files", "--stage")
        self.source.write_text("export const value = 2;\n")
        self.config.write_text('{"semi": true}\n')

        def validate(target, installed):
            self.assertEqual((target / "src" / "example.tsx").read_text(), "export const value = 1\n")
            self.assertEqual((target / ".prettierrc.json").read_text(), '{"semi": false}\n')
            self.assertEqual((target / "node_modules").resolve(), installed)

        with patch.object(frontend, "validate", side_effect=validate) as run:
            frontend.check(self.root)
            run.assert_called_once()
        self.assertEqual(self.git("ls-files", "--stage"), before)
        self.assertEqual(self.source.read_text(), "export const value = 2;\n")

    def test_failure_blocks_check_and_removes_temporary_snapshot(self):
        before = self.git("ls-files", "--stage")
        targets = []

        def fail(target, installed):
            targets.append(target)
            raise subprocess.CalledProcessError(1, ["prettier"])

        with patch.object(frontend, "validate", side_effect=fail):
            with self.assertRaises(subprocess.CalledProcessError):
                frontend.check(self.root)
        self.assertFalse(targets[0].exists())
        self.assertEqual(self.git("ls-files", "--stage"), before)

    def test_staged_deletion_does_not_check_the_remaining_worktree_file(self):
        self.git("rm", "--cached", "--quiet", "--", "frontend/src/example.tsx")
        self.assertTrue(self.source.exists())
        def validate(target, installed):
            self.assertFalse((target / "src" / "example.tsx").exists())

        with patch.object(frontend, "validate", side_effect=validate) as run:
            frontend.check(self.root)
            run.assert_called_once()

    def test_backend_only_commit_does_not_require_frontend_dependencies(self):
        self.git("rm", "--cached", "-r", "--quiet", "--", "frontend")
        (self.root / "backend.txt").write_text("backend change\n")
        self.git("add", "backend.txt")
        with patch.object(frontend, "validate") as run:
            frontend.check(self.root)
            run.assert_not_called()

    def test_missing_tools_fail_with_install_instruction(self):
        (self.root / "frontend" / "node_modules" / ".bin" / "prettier").unlink()
        with self.assertRaisesRegex(ValueError, "bun install --frozen-lockfile"):
            frontend.check(self.root)

    def test_symlinked_staged_source_is_rejected(self):
        self.source.unlink()
        self.source.symlink_to("/etc/hosts")
        self.git("add", "frontend/src/example.tsx")
        with self.assertRaisesRegex(ValueError, "regular file"):
            frontend.check(self.root)


class PrePushTests(unittest.TestCase):
    def test_checks_frontend_and_propagates_failure_but_skips_deletions(self):
        hook = Path(__file__).resolve().parents[1] / ".githooks" / "pre-push"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "bin").mkdir()
            (root / "scripts").mkdir()
            fake_git = root / "bin" / "git"
            fake_git.write_text('#!/bin/sh\nprintf "%s\\n" "$QA_REPO"\n')
            fake_git.chmod(0o755)
            check = root / "scripts" / "check.sh"
            check.write_text('#!/bin/sh\nprintf "%s" "$1" > called\nexit 7\n')
            check.chmod(0o755)
            env = {**os.environ, "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"],
                   "QA_REPO": str(root)}
            result = subprocess.run([str(hook)], input="refs/heads/test abc refs/heads/test def\n",
                                    text=True, env=env, cwd=root)
            self.assertEqual(result.returncode, 7)
            self.assertEqual((root / "called").read_text(), "frontend")
            (root / "called").unlink()
            for data in ("", "(delete) " + "0" * 40 + " refs/heads/test abc\n"):
                result = subprocess.run([str(hook)], input=data, text=True, env=env, cwd=root)
                self.assertEqual(result.returncode, 0)
                self.assertFalse((root / "called").exists())


if __name__ == "__main__":
    unittest.main()
