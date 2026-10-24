#!/usr/bin/env python3
"""Dev environment and isolated checks."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, build_opener
import uuid

from dev_storage import prepare_bucket


ROOT = Path(__file__).resolve().parent.parent
LOCAL_NAME = "loopcraft-local-" + hashlib.sha256(str(ROOT).encode()).hexdigest()[:8]
IMAGE = "docker.io/library/postgres:17-alpine"
STORAGE_NAME = LOCAL_NAME.replace("local", "rustfs", 1)
STORAGE_IMAGE = "docker.io/rustfs/rustfs@sha256:128220dc9382bac81940a1a9616ee8c274a3f3b8f647eedbeb50df5bb8b49922"


def read_env(path):
    """Read literal dotenv values; never execute files as shell programs."""
    values = {}
    if not path.exists():
        return values
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, raw = line.partition("=")
        key = key.strip()
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError(f"{path.name}:{number}: expected NAME=value")
        try:
            parts = shlex.split(raw, comments=True)
        except ValueError:
            raise ValueError(f"{path.name}:{number}: invalid quoting") from None
        if len(parts) > 1:
            raise ValueError(f"{path.name}:{number}: quote values containing spaces")
        values[key] = parts[0] if parts else ""
    return values


def environment():
    # Process environment takes precedence over the optional local file.
    return {**read_env(ROOT / "env" / "dev" / ".env"), **os.environ}


def test_environment():
    # Tests never load dotenv files or inherit application/model credentials.
    prefixes = ("SPRING_", "LOOPCRAFT_", "LLM_", "DB_", "TEST_DB_", "AGENT_", "LOGGING_", "BOOTSTRAP_ADMIN_", "SESSION_COOKIE_", "STORAGE_", "AWS_", "RUSTFS_", "SKILL_")
    excluded = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS", "ENABLE_LLM",
                "LOG_DIR", "LOG_LEVEL_ROOT", "LOG_LEVEL_APP"}
    return {key: value for key, value in os.environ.items()
            if not key.startswith(prefixes) and key not in excluded}


def port(value):
    result = int(value)
    if not 1 <= result <= 65535:
        raise ValueError("Port must be between 1 and 65535")
    return result


def local_environment():
    env = environment()
    defaults = {"DB_PORT": "15432", "DB_NAME": "loopcraft_local",
                "DB_USER": "loopcraft", "DB_PASSWORD": "loopcraft_local_only",
                "SERVER_PORT": "8080", "FRONTEND_PORT": "5173",
                "STORAGE_ENABLED": "true", "STORAGE_ENDPOINT": "http://127.0.0.1:9000",
                "STORAGE_REGION": "us-east-1", "SKILL_BUCKET": "loopcraft-skills",
                "RUSTFS_CONSOLE_PORT": "9001", "RUSTFS_MANAGED": "true"}
    for key, value in defaults.items():
        env.setdefault(key, value)
    env.setdefault("LOG_DIR", str(ROOT / ".run" / "logs"))
    log_dir = Path(env["LOG_DIR"])
    env["LOG_DIR"] = str(log_dir if log_dir.is_absolute() else ROOT / log_dir)
    for key in ("DB_PORT", "SERVER_PORT", "FRONTEND_PORT"):
        port(env[key])
    for key in ("DB_NAME", "DB_USER"):
        if not re.fullmatch(r"[a-z][a-z0-9_]*", env[key]):
            raise ValueError(f"{key} must use lowercase letters, numbers and underscores")
    if not env["DB_PASSWORD"]:
        raise ValueError("DB_PASSWORD must not be empty")
    # Local commands always target their own loopback database.
    env = {key: value for key, value in env.items() if not key.startswith("SPRING_")}
    env["DB_URL"] = f"jdbc:postgresql://127.0.0.1:{env['DB_PORT']}/{env['DB_NAME']}"
    env["BACKEND_URL"] = f"http://127.0.0.1:{env['SERVER_PORT']}"
    return env


def runtime():
    requested = os.environ.get("CONTAINER_RUNTIME")
    candidates = [requested] if requested else ["docker", "podman"]
    for candidate in candidates:
        if candidate in ("docker", "podman") and shutil.which(candidate):
            return candidate
    raise ValueError("Install Docker or Podman; CONTAINER_RUNTIME can select one")


def run(command, *, env=None, cwd=ROOT, capture=False, **kwargs):
    process = subprocess.Popen(command, cwd=cwd, env=env, text=True,
                               stdout=subprocess.PIPE if capture else None,
                               stderr=subprocess.PIPE if capture else None,
                               start_new_session=True, **kwargs)
    try:
        output, errors = process.communicate()
    except BaseException:
        stop_children([process])
        raise
    if process.returncode:
        if capture and errors:
            print(errors, file=sys.stderr, end="")
        raise subprocess.CalledProcessError(process.returncode, command, output, errors)
    return subprocess.CompletedProcess(command, process.returncode, output, errors)


def maven(*arguments):
    command = [str(ROOT / "backend" / "mvnw"), "-B", "-ntp"]
    if os.environ.get("MAVEN_REPO_LOCAL"):
        command.append("-Dmaven.repo.local=" + os.environ["MAVEN_REPO_LOCAL"])
    return command + list(arguments)


def inspect_container(engine, name):
    result = subprocess.run([engine, "container", "inspect", name],
                            capture_output=True, text=True)
    if result.returncode:
        # Distinguish a missing container from an unavailable runtime.
        run([engine, "info"], capture=True)
        return None
    return json.loads(result.stdout)[0]


def owned_container(engine):
    info = inspect_container(engine, LOCAL_NAME)
    if info and info["Config"].get("Labels", {}).get("loopcraft.root") != str(ROOT):
        raise ValueError("Refusing to operate on a container owned by another workspace")
    return info


def postgres_environment(env):
    return {**env, "POSTGRES_DB": env["DB_NAME"], "POSTGRES_USER": env["DB_USER"],
            "POSTGRES_PASSWORD": env["DB_PASSWORD"], "PGPASSWORD": env["DB_PASSWORD"]}


def wait_database(engine, name, env):
    command = [engine, "exec", "--env", "PGPASSWORD", name, "psql", "-h", "127.0.0.1",
               "-U", env["DB_USER"], "-d", env["DB_NAME"], "-v", "ON_ERROR_STOP=1",
               "-c", "SELECT 1"]
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        result = subprocess.run(command, env=postgres_environment(env),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=5)
        if result.returncode == 0:
            return
        time.sleep(1)
    raise ValueError("PostgreSQL was not ready in 60s; check container logs and local credentials")


def database_up(engine, env):
    info = owned_container(engine)
    if info:
        bindings = info["HostConfig"]["PortBindings"].get("5432/tcp", [])
        if not any(item["HostPort"] == env["DB_PORT"] and item["HostIp"] == "127.0.0.1"
                   for item in bindings):
            raise ValueError("DB_PORT changed; run db down before db up (volume is retained)")
        if not info["State"]["Running"]:
            run([engine, "start", LOCAL_NAME], capture=True)
    else:
        run([engine, "run", "--detach", "--name", LOCAL_NAME,
             "--label", "loopcraft.root=" + str(ROOT),
             "--publish", f"127.0.0.1:{env['DB_PORT']}:5432",
             "--volume", LOCAL_NAME + "-data:/var/lib/postgresql/data",
             "--env", "POSTGRES_DB", "--env", "POSTGRES_USER", "--env", "POSTGRES_PASSWORD",
             env.get("LOOPCRAFT_POSTGRES_IMAGE", IMAGE)],
            env=postgres_environment(env), capture=True)
    wait_database(engine, LOCAL_NAME, env)
    print(f"PostgreSQL ready on 127.0.0.1:{env['DB_PORT']} ({LOCAL_NAME})", flush=True)


def storage_container(engine, env):
    name = env.get("RUSTFS_CONTAINER", STORAGE_NAME)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name):
        raise ValueError("Invalid RUSTFS_CONTAINER name")
    info = inspect_container(engine, name)
    if info:
        labels = info["Config"].get("Labels") or {}
        owned = labels.get("loopcraft.root") == str(ROOT)
        # Reuse an explicitly selected, previously installed local RustFS.
        shared = "RUSTFS_CONTAINER" in env and labels.get("io.local.rustfs") == "true" and not labels.get("loopcraft.root")
        if not (owned or shared):
            raise ValueError("Refusing to operate on a RustFS container owned by another workspace")
    return name, info


def storage_credentials(env, managed):
    configured = [bool(env.get(key)) for key in ("STORAGE_ACCESS_KEY", "STORAGE_SECRET_KEY")]
    if any(configured) and not all(configured):
        raise ValueError("Configure both STORAGE_ACCESS_KEY and STORAGE_SECRET_KEY")
    if all(configured):
        return
    if not managed:
        raise ValueError("External storage requires STORAGE_ACCESS_KEY and STORAGE_SECRET_KEY")
    path = ROOT / ".run" / "storage.env"
    path.parent.mkdir(exist_ok=True)
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        saved = read_env(path)
    else:
        saved = {"STORAGE_ACCESS_KEY": secrets.token_hex(16),
                 "STORAGE_SECRET_KEY": secrets.token_hex(32)}
        with os.fdopen(descriptor, "w") as target:
            target.write("".join(f"{key}={value}\n" for key, value in saved.items()))
    if not all(saved.get(key) for key in ("STORAGE_ACCESS_KEY", "STORAGE_SECRET_KEY")):
        raise ValueError("Invalid .run/storage.env; configure storage credentials explicitly")
    env.update({key: saved[key] for key in ("STORAGE_ACCESS_KEY", "STORAGE_SECRET_KEY")})


def storage_up(engine, env):
    if env["STORAGE_ENABLED"].lower() == "false":
        print("File storage disabled by STORAGE_ENABLED=false", flush=True)
        return
    if env["STORAGE_ENABLED"].lower() != "true" or env["RUSTFS_MANAGED"].lower() not in ("true", "false"):
        raise ValueError("STORAGE_ENABLED and RUSTFS_MANAGED must be true or false")
    managed = env["RUSTFS_MANAGED"].lower() == "true"
    endpoint = urlsplit(env["STORAGE_ENDPOINT"])
    if managed:
        if endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or endpoint.path not in ("", "/") or endpoint.query or endpoint.fragment or endpoint.username or endpoint.password:
            raise ValueError("Managed RustFS requires a loopback HTTP STORAGE_ENDPOINT; set RUSTFS_MANAGED=false for external storage")
        api_port, console_port = str(port(endpoint.port or 80)), str(port(env["RUSTFS_CONSOLE_PORT"]))
        if api_port == console_port:
            raise ValueError("RustFS API and console ports must differ")
        name, info = storage_container(engine, env)
    storage_credentials(env, managed)
    if managed:
        if info:
            bindings = info["HostConfig"]["PortBindings"]
            for internal, external in (("9000/tcp", api_port), ("9001/tcp", console_port)):
                if not any(item["HostPort"] == external and item["HostIp"] == "127.0.0.1" for item in bindings.get(internal, [])):
                    raise ValueError("RustFS port mapping differs; update local configuration (data volume retained)")
            values = dict(item.split("=", 1) for item in info["Config"].get("Env", []) if "=" in item)
            if any(values.get("RUSTFS_" + suffix) != env["STORAGE_" + suffix] for suffix in ("ACCESS_KEY", "SECRET_KEY")):
                raise ValueError("Storage credentials differ from existing RustFS; configure the matching local credentials")
            if not info["State"]["Running"]:
                run([engine, "start", name], capture=True)
        else:
            run([engine, "run", "--detach", "--name", name,
                 "--memory", "2g", "--cpus", "1", "--pids-limit", "512",
                 "--label", "loopcraft.root=" + str(ROOT),
                 "--publish", f"127.0.0.1:{api_port}:9000",
                 "--publish", f"127.0.0.1:{console_port}:9001",
                 "--volume", name + "-data:/data:Z",
                 "--env", "RUSTFS_ACCESS_KEY", "--env", "RUSTFS_SECRET_KEY",
                 "--env", "RUSTFS_ADDRESS=:9000", "--env", "RUSTFS_CONSOLE_ADDRESS=:9001",
                 "--env", "RUSTFS_CONSOLE_ENABLE=true", STORAGE_IMAGE, "/data"],
                env={**env, "RUSTFS_ACCESS_KEY": env["STORAGE_ACCESS_KEY"],
                     "RUSTFS_SECRET_KEY": env["STORAGE_SECRET_KEY"]}, capture=True)
        opener = build_opener(ProxyHandler({}))
        deadline = time.monotonic() + 60
        while True:
            try:
                with opener.open(env["STORAGE_ENDPOINT"].rstrip("/") + "/health/ready", timeout=2) as response:
                    if response.status == 200:
                        break
            except (URLError, TimeoutError, ConnectionError):
                pass
            if time.monotonic() >= deadline:
                raise ValueError("RustFS was not ready in 60s; check container logs")
            time.sleep(0.5)
    prepare_bucket(env, create=managed)
    print(f"File storage ready: {env['STORAGE_ENDPOINT']} (Skill bucket: {env['SKILL_BUCKET']})", flush=True)


def storage_command(action):
    env, engine = local_environment(), runtime()
    if action == "up":
        storage_up(engine, env)
    else:
        if env["RUSTFS_MANAGED"].lower() != "true":
            raise ValueError("External storage is not managed by this launcher")
        name, info = storage_container(engine, env)
        if action == "down":
            if info and info["State"]["Running"]:
                run([engine, "stop", "--time", "10", name], capture=True)
            print("RustFS stopped; container and data volume retained")
        else:
            print("absent" if info is None else info["State"]["Status"])


def database_command(action, sql_file):
    env, engine = local_environment(), runtime()
    if action == "up":
        database_up(engine, env)
    elif action == "down":
        info = owned_container(engine)
        if info:
            if info["State"]["Running"]:
                run([engine, "stop", "--time", "10", LOCAL_NAME], capture=True)
            run([engine, "rm", LOCAL_NAME], capture=True)
        print("Local database container removed; data volume retained")
    elif action == "status":
        info = owned_container(engine)
        print("absent" if info is None else info["State"]["Status"])
    else:
        directory = (ROOT / "backend" / "sql").resolve()
        path = (directory / (sql_file or "")).resolve()
        if not path.is_relative_to(directory) or path.suffix != ".sql" or not path.is_file():
            raise ValueError("Choose an existing .sql file inside backend/sql")
        database_up(engine, env)
        with path.open() as source:
            run([engine, "exec", "--interactive", "--env", "PGPASSWORD", LOCAL_NAME,
                 "psql", "-h", "127.0.0.1", "-U", env["DB_USER"], "-d", env["DB_NAME"],
                 "--single-transaction", "-v", "ON_ERROR_STOP=1"],
                env=postgres_environment(env), stdin=source)
        print("Applied " + str(path.relative_to(directory)) + "; record this version manually")


def stop_children(children):
    # Only signal process groups created by this invocation.
    for child in children:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 10
    for child in children:
        try:
            child.wait(timeout=max(0.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()


def wait_http(url, children):
    opener = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if any(child.poll() is not None for child in children):
            raise ValueError("A development process exited; inspect .run/*.log")
        try:
            with opener.open(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.5)
    raise ValueError("Service startup timed out; inspect .run/*.log")


def development(scope):
    env = local_environment()
    selected = "dev"
    services = []
    if scope in ("all", "backend"):
        services.append(("backend", port(env["SERVER_PORT"]),
                         maven("spring-boot:run", "-Dspring-boot.run.profiles=" + selected),
                         ROOT / "backend", "/actuator/health"))
    if scope in ("all", "frontend"):
        if not (ROOT / "frontend" / "node_modules").exists():
            raise ValueError("Install frontend dependencies first: cd frontend && bun install --frozen-lockfile")
        services.append(("frontend", port(env["FRONTEND_PORT"]), ["bun", "run", "dev"],
                         ROOT / "frontend", "/"))
    if len({service[1] for service in services}) != len(services):
        raise ValueError("Backend and frontend ports must differ")
    for _, service_port, _, _, _ in services:
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", service_port))
    if scope in ("all", "backend"):
        engine = runtime()
        database_up(engine, env)
        storage_up(engine, env)
    log_dir = ROOT / ".run"
    log_dir.mkdir(exist_ok=True)
    children, logs = [], []
    try:
        for name, _, command, cwd, _ in services:
            log = (log_dir / f"{name}.log").open("w")
            logs.append(log)
            children.append(subprocess.Popen(command, cwd=cwd, env=env,
                                             stdout=log, stderr=subprocess.STDOUT,
                                             start_new_session=True))
        for name, service_port, _, _, path in services:
            url = f"http://127.0.0.1:{service_port}"
            wait_http(url + path, children)
            print(f"{name} ready: {url} (log: .run/{name}.log)", flush=True)
        if scope in ("all", "backend"):
            print(f"Application log: {Path(env['LOG_DIR']) / 'loopcraft.log'}", flush=True)
        print("Ctrl+C stops these application processes; PostgreSQL and RustFS remain running; data is retained", flush=True)
        while True:
            if any(child.poll() is not None for child in children):
                raise ValueError("A development process exited; inspect .run/*.log")
            time.sleep(0.5)
    finally:
        stop_children(children)
        for log in logs:
            log.close()


def integration_tests():
    env, engine = test_environment(), runtime()
    name = "loopcraft-test-" + uuid.uuid4().hex[:12]
    env.update(DB_NAME="loopcraft_test", DB_USER="loopcraft_test", DB_PASSWORD=uuid.uuid4().hex)
    created = False
    try:
        run([engine, "run", "--detach", "--name", name,
             "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data",
             "--env", "POSTGRES_DB", "--env", "POSTGRES_USER", "--env", "POSTGRES_PASSWORD",
             env.get("LOOPCRAFT_POSTGRES_IMAGE", IMAGE)],
            env=postgres_environment(env), capture=True)
        created = True
        wait_database(engine, name, env)
        mapping = run([engine, "port", name, "5432/tcp"], capture=True).stdout.strip()
        test_port = port(mapping.rsplit(":", 1)[1])
        env.update(TEST_DB_URL=f"jdbc:postgresql://127.0.0.1:{test_port}/loopcraft_test",
                   TEST_DB_USER=env["DB_USER"], TEST_DB_PASSWORD=env["DB_PASSWORD"])
        run(maven("-Ppostgres-it", "verify"), env=env, cwd=ROOT / "backend")
    finally:
        if created:
            run([engine, "rm", "--force", name], capture=True)


def tests(scope):
    env = test_environment()
    if scope == "integration":
        integration_tests()
        return
    if scope in ("repo", "all"):
        run([sys.executable, "scripts/check_repo.py"], env=env)
        run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"], env=env)
    if scope in ("backend", "all"):
        run(maven("verify"), env=env, cwd=ROOT / "backend")
    if scope in ("frontend", "all"):
        run(["bun", "run", "check"], env=env, cwd=ROOT / "frontend")
        run(["bun", "run", "test"], env=env, cwd=ROOT / "frontend")
        run(["bun", "run", "build"], env=env, cwd=ROOT / "frontend")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    up = commands.add_parser("up", help="Run local apps in foreground; Ctrl+C stops them")
    up.add_argument("scope", choices=("all", "backend", "frontend"), nargs="?", default="all")
    db = commands.add_parser("db", help="Manage only this workspace's local PostgreSQL")
    db.add_argument("action", choices=("up", "down", "status", "sql"))
    db.add_argument("sql_file", nargs="?")
    storage = commands.add_parser("storage", help="Manage local RustFS without deleting data")
    storage.add_argument("action", choices=("up", "down", "status"))
    test = commands.add_parser("test", help="Checks without real model calls; integration uses a disposable DB")
    test.add_argument("scope", choices=("repo", "backend", "frontend", "all", "integration"),
                      nargs="?", default="all")
    args = parser.parse_args()
    if args.command == "up":
        development(args.scope)
    elif args.command == "db":
        if (args.action == "sql") != bool(args.sql_file):
            parser.error("db sql requires a path; other db commands take no path")
        database_command(args.action, args.sql_file)
    elif args.command == "storage":
        storage_command(args.action)
    elif args.command == "test":
        tests(args.scope)


if __name__ == "__main__":
    def interrupt(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit(130)
    except subprocess.CalledProcessError as error:
        print(f"Command failed (exit {error.returncode})", file=sys.stderr)
        raise SystemExit(error.returncode)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
