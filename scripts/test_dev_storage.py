"""Storage lifecycle, credentials and startup failure checks; no real services."""

from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import dev
import dev_storage


class StorageTests(unittest.TestCase):
    def environment(self):
        return {"STORAGE_ENABLED": "true", "RUSTFS_MANAGED": "true",
                "STORAGE_ENDPOINT": "http://127.0.0.1:9000", "RUSTFS_CONSOLE_PORT": "9001",
                "STORAGE_REGION": "us-east-1", "SKILL_BUCKET": "loopcraft-skills",
                "STORAGE_ACCESS_KEY": "test-key", "STORAGE_SECRET_KEY": "test-secret"}

    def test_disabled_storage_does_not_touch_runtime_or_bucket(self):
        with patch.object(dev, "storage_container") as inspect, patch.object(dev, "prepare_bucket") as prepare:
            dev.storage_up("podman", {"STORAGE_ENABLED": "false"})
            inspect.assert_not_called()
            prepare.assert_not_called()

    def test_rejects_foreign_container_even_when_explicitly_named(self):
        env = self.environment() | {"RUSTFS_CONTAINER": "foreign"}
        for labels in ({}, {"loopcraft.root": "/other", "io.local.rustfs": "true"}):
            with patch.object(dev, "inspect_container", return_value={"Config": {"Labels": labels}}):
                with self.assertRaisesRegex(ValueError, "another workspace"):
                    dev.storage_container("podman", env)

    def test_explicit_shared_local_container_is_reused(self):
        info = {"Config": {"Labels": {"io.local.rustfs": "true"}}}
        with patch.object(dev, "inspect_container", return_value=info):
            name, found = dev.storage_container("podman", self.environment() | {"RUSTFS_CONTAINER": "local-rustfs"})
            self.assertEqual(name, "local-rustfs")
            self.assertIs(found, info)

    def test_generated_credentials_are_private_and_reused(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(dev, "ROOT", Path(directory)):
            env = {}
            dev.storage_credentials(env, True)
            self.assertEqual(stat.S_IMODE((Path(directory) / ".run/storage.env").stat().st_mode), 0o600)
            again = {}
            dev.storage_credentials(again, True)
            self.assertEqual(again, env)
            self.assertGreaterEqual(len(env["STORAGE_SECRET_KEY"]), 32)

    def test_partial_credentials_fail_without_generating_replacements(self):
        with patch.object(dev, "read_env") as read:
            with self.assertRaisesRegex(ValueError, "both STORAGE"):
                dev.storage_credentials({"STORAGE_ACCESS_KEY": "private-key"}, True)
            read.assert_not_called()

    def test_managed_storage_rejects_external_endpoint_before_runtime(self):
        with patch.object(dev, "storage_container") as inspect:
            with self.assertRaisesRegex(ValueError, "loopback"):
                dev.storage_up("podman", self.environment() | {"STORAGE_ENDPOINT": "https://external.example"})
            inspect.assert_not_called()

    def test_external_storage_only_checks_existing_bucket(self):
        with patch.object(dev, "storage_container") as inspect, patch.object(dev, "prepare_bucket") as prepare:
            env = self.environment() | {"RUSTFS_MANAGED": "false"}
            dev.storage_up("podman", env)
            inspect.assert_not_called()
            prepare.assert_called_once_with(env, create=False)

    def test_mismatching_existing_credentials_fail_before_start(self):
        info = {"Config": {"Env": ["RUSTFS_ACCESS_KEY=other", "RUSTFS_SECRET_KEY=other"]},
                "HostConfig": {"PortBindings": {"9000/tcp": [{"HostIp": "127.0.0.1", "HostPort": "9000"}],
                                                "9001/tcp": [{"HostIp": "127.0.0.1", "HostPort": "9001"}]}},
                "State": {"Running": False}}
        with patch.object(dev, "storage_container", return_value=("local-rustfs", info)), patch.object(dev, "run") as run:
            with self.assertRaisesRegex(ValueError, "credentials differ") as error:
                dev.storage_up("podman", self.environment())
            self.assertNotIn("test-secret", str(error.exception))
            run.assert_not_called()

    def test_missing_bucket_is_created_and_verified(self):
        env = self.environment()
        with patch.object(dev_storage, "s3_request", side_effect=[(404, b""), (200, b""), (200, b"")]) as request:
            dev_storage.prepare_bucket(env)
            self.assertEqual([call.args[1] for call in request.call_args_list], ["HEAD", "PUT", "HEAD"])

    def test_access_denied_does_not_create_bucket_or_echo_server_body(self):
        with patch.object(dev_storage, "s3_request", return_value=(403, b"private credentials")) as request:
            with self.assertRaisesRegex(ValueError, "HTTP 403") as error:
                dev_storage.prepare_bucket(self.environment())
            self.assertNotIn("private credentials", str(error.exception))
            self.assertEqual(request.call_count, 1)

    def test_bucket_failure_blocks_backend_start(self):
        with patch.object(dev, "local_environment", return_value=self.environment() | {"SERVER_PORT": "8080"}), \
                patch.object(dev, "runtime", return_value="podman"), patch.object(dev.socket, "socket"), \
                patch.object(dev, "database_up"), patch.object(dev, "storage_up", side_effect=ValueError("bucket unavailable")), \
                patch.object(dev.subprocess, "Popen") as start:
            with self.assertRaisesRegex(ValueError, "bucket unavailable"):
                dev.development("backend")
            start.assert_not_called()


if __name__ == "__main__":
    unittest.main()
