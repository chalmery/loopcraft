"""Checks for environment isolation and ownership/cleanup of development resources."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import dev


class EnvironmentTests(unittest.TestCase):
    def test_env_values_are_literal_and_not_executed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("DB_PASSWORD='$(touch sentinel); `id` # literal'\n")
            values = dev.read_env(path)
            self.assertEqual(values["DB_PASSWORD"], "$(touch sentinel); `id` # literal")
            self.assertFalse((Path(directory) / "sentinel").exists())

    def test_malformed_env_error_does_not_echo_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("DB_PASSWORD='sensitive-value\n")
            with self.assertRaises(ValueError) as error:
                dev.read_env(path)
            self.assertNotIn("sensitive-value", str(error.exception))

    def test_tests_remove_inherited_service_configuration(self):
        with patch.dict(os.environ, {"PATH": "/bin", "DB_URL": "external-db",
                                    "TEST_DB_PASSWORD": "external-secret",
                                    "SPRING_PROFILES_ACTIVE": "dev", "LLM_ENCRYPTION_KEY": "secret",
                                    "JAVA_TOOL_OPTIONS": "-Dspring.profiles.active=dev",
                                    "LOG_DIR": "/external/logs", "LOG_LEVEL_APP": "TRACE",
                                    "LOGGING_FILE_NAME": "/external/server.log",
                                    "LOOPCRAFT_IDENTITY_LOGINPROTECTION_ACCOUNTFAILURES": "1",
                                    "BOOTSTRAP_ADMIN_ENABLED": "true", "BOOTSTRAP_ADMIN_PASSWORD": "secret",
                                    "SESSION_COOKIE_SECURE": "true", "STORAGE_ENABLED": "true",
                                    "STORAGE_SECRET_KEY": "external-secret", "RUSTFS_SECRET_KEY": "external-secret",
                                    "AWS_ACCESS_KEY_ID": "external-key", "AWS_SECRET_ACCESS_KEY": "external-secret",
                                    "AWS_ENDPOINT_URL": "https://external.example"}, clear=True):
            self.assertEqual(dev.test_environment(), {"PATH": "/bin"})

    def test_launcher_only_reads_dev_configuration(self):
        with patch.dict(os.environ, {"SERVER_PORT": "8081"}, clear=True), \
                patch.object(dev, "read_env", return_value={"SERVER_PORT": "8082"}) as read:
            self.assertEqual(dev.environment()["SERVER_PORT"], "8081")
            read.assert_called_once_with(dev.ROOT / "env" / "dev" / ".env")

    def test_local_url_cannot_inherit_an_external_database(self):
        with patch.dict(os.environ, {"DB_URL": "external-db", "DB_PORT": "15439",
                                    "SPRING_DATASOURCE_URL": "another-external-db"}, clear=True), \
                patch.object(dev, "read_env", return_value={}):
            env = dev.local_environment()
            self.assertEqual(env["DB_URL"], "jdbc:postgresql://127.0.0.1:15439/loopcraft_local")
            self.assertNotIn("SPRING_DATASOURCE_URL", env)


class ResourceTests(unittest.TestCase):
    def test_database_owned_by_another_workspace_is_rejected(self):
        info = {"Config": {"Labels": {"loopcraft.root": "/another/workspace"}}}
        with patch.object(dev, "inspect_container", return_value=info):
            with self.assertRaisesRegex(ValueError, "another workspace"):
                dev.owned_container("podman")

    def test_sql_path_escape_is_rejected_before_database_start(self):
        with patch.object(dev, "runtime", return_value="podman"), \
                patch.object(dev, "local_environment", return_value={}), \
                patch.object(dev, "database_up") as start:
            with self.assertRaisesRegex(ValueError, "inside backend/sql"):
                dev.database_command("sql", "../../../outside.sql")
            start.assert_not_called()

    def test_failed_integration_test_still_removes_its_container(self):
        def command(args, **kwargs):
            if args[0].endswith("mvnw"):
                raise subprocess.CalledProcessError(1, args)
            return subprocess.CompletedProcess(args, 0, stdout="127.0.0.1:15439\n")

        with patch.object(dev, "runtime", return_value="podman"), \
                patch.object(dev, "wait_database"), \
                patch.object(dev, "run", side_effect=command) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                dev.integration_tests()
            created_name = run.call_args_list[0].args[0][4]
            self.assertTrue(created_name.startswith("loopcraft-test-"))
            self.assertEqual(run.call_args_list[-1].args[0],
                             ["podman", "rm", "--force", created_name])


if __name__ == "__main__":
    unittest.main()
