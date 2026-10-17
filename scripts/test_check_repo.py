from pathlib import Path
import subprocess
import tempfile
import unittest

from check_repo import check


class RepositoryCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args])

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def run_check(self, staged=False):
        # Run through the CLI so Git reads the temporary index rather than this repo.
        script = Path(__file__).with_name("check_repo.py")
        return subprocess.run(
            ["python3", str(script), *(["--staged"] if staged else [])],
            cwd=self.root, capture_output=True, text=True,
        )

    def test_safe_example_is_allowed(self):
        self.write("backend/.env.example", "LLM_API_KEY=replace-me\n")
        self.assertEqual(self.run_check().returncode, 0)

    def test_untracked_credential_is_blocked_without_disclosure(self):
        secret = "sk-" + "A" * 30
        self.write("key.txt", secret)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(secret, result.stdout + result.stderr)

    def test_staged_content_is_used_instead_of_clean_worktree(self):
        self.write("key.txt", "sk-" + "A" * 30)
        self.git("add", "key.txt")
        self.write("key.txt", "safe\n")
        self.assertEqual(self.run_check(staged=True).returncode, 1)

    def test_unstaged_secret_does_not_block_safe_staged_blob(self):
        self.write("key.txt", "safe\n")
        self.git("add", "key.txt")
        self.write("key.txt", "sk-" + "A" * 30)
        self.assertEqual(self.run_check(staged=True).returncode, 0)

    def test_forced_local_environment_is_blocked(self):
        self.write(".env", "LLM_MODEL=local\n")
        self.git("add", ".env")
        self.assertEqual(self.run_check(staged=True).returncode, 1)

    def test_ignored_reference_checkout_is_not_read(self):
        self.write(".gitignore", "harness-references/\n")
        self.write("harness-references/example/key.txt", "sk-" + "A" * 30)
        self.assertEqual(self.run_check().returncode, 0)

    def test_whitespace_errors_do_not_echo_sensitive_source(self):
        secret = "sk-" + "A" * 30
        self.write("key.txt", secret + "  \n")
        self.git("add", "key.txt")
        result = self.run_check(staged=True)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(secret, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
