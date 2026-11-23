"""Private credential transport for reviewed local SQL. Not a SQL sandbox."""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile


class Refused(Exception):
    """Safe, non-secret diagnostic."""


def private_values(path):
    # Fail closed on Windows until ACL checks can be verified on that platform.
    if os.name != "posix":
        raise Refused("Windows ACL validation is not supported yet; use WSL with a private Linux file.")
    repo = Path(__file__).resolve().parents[2]
    if path.is_symlink() or path.resolve().is_relative_to(repo):
        raise Refused("The secret file must be outside the repository and must not be a symlink.")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "r", encoding="utf-8") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) not in (0o400, 0o600):
            raise Refused("Secret file must be owned by you with mode 600 or 400; connection refused.")
        if info.st_nlink != 1 or info.st_size > 16384:
            raise Refused("Secret file must be a small private file with no hard links.")
        if sys.platform == "darwin":
            acl = subprocess.run(["/bin/ls", "-lde", str(path)], text=True, capture_output=True, check=False)
            if (acl.returncode or not acl.stdout.split() or "+" in acl.stdout.split()[0]
                    or re.search(r"(?m)^\s*\d+:", acl.stdout)):
                raise Refused("Secret file has an extended ACL or its ACL could not be checked; remove the ACL first.")
            # Ensure the checked pathname still names the opened file.
            current = os.stat(path, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
                raise Refused("Secret file changed during permission validation.")
        values = {}
        for line in stream.read(16385).splitlines():
            if not line or line.startswith("#"):
                continue
            key, separator, value = line.partition("=")
            if not separator or key not in ("USERNAME", "PASSWORD", "WALLET_PATH", "TNS_ALIAS") or key in values:
                raise Refused("Invalid private file format; use literal unique key=value lines.")
            values[key] = value
    if set(values) != {"USERNAME", "PASSWORD", "WALLET_PATH", "TNS_ALIAS"} or not all(values.values()):
        raise Refused("Complete all four private file fields manually.")
    if any(any(ord(c) < 32 or ord(c) == 127 for c in v) for v in values.values()):
        raise Refused("Control characters are not supported in private file values.")
    if '"' in values["PASSWORD"]:
        raise Refused("Double quotes in passwords are not supported by this wrapper.")
    return values


def run(config_path, script_path):
    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    if not isinstance(config, dict) or any(not isinstance(config.get(key), str) for key in
            ("username", "tnsAlias", "walletPath", "prefix", "outputDirectory")):
        raise Refused("Invalid scenario configuration fields.")
    secret = private_values(Path.home() / ".dbcompanion" / "scenario.env")
    username, alias = secret["USERNAME"], secret["TNS_ALIAS"]
    if not Path(secret["WALLET_PATH"]).is_absolute():
        raise Refused("The private wallet path must be absolute.")
    if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,29}", username) or username in {"SYS", "SYSTEM", "ADMIN", "PUBLIC"}:
        raise Refused("Use an unquoted non-administrator scenario username.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", alias):
        raise Refused("Invalid TNS alias.")
    if (config.get("version") != 1 or config.get("username") != username or config.get("tnsAlias") != alias
            or Path(config.get("walletPath", "")).resolve() != Path(secret["WALLET_PATH"]).resolve()):
        raise Refused("Private connection target does not match scenario-config.json; connection refused.")
    prefix = config.get("prefix", "")
    if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,14}_", prefix):
        raise Refused("Invalid table prefix.")
    sql_path = Path(script_path).resolve()
    output = Path(config.get("outputDirectory", "")).resolve()
    repo = Path(__file__).resolve().parents[2]
    if output != (repo / "scenario-results" / prefix).resolve() or not sql_path.is_relative_to(output):
        raise Refused("Run from the checkout root with SQL inside the configured scenario-results folder.")
    script = sql_path.read_text(encoding="utf-8")
    # SQL is reviewed by the user/agent. This guard catches accidental extra SQLcl
    # sessions, but deliberately does not pretend to parse or sandbox Oracle SQL.
    if re.search(r"(?im)^\s*(?:@|host\b|spool\b|conn(?:ect)?\b|start\b|script\b|set\s+(?:echo|define)\b)", script):
        raise Refused("SQLcl connection, scripting, echo and spool commands are not allowed in the SQL file.")
    password = secret["PASSWORD"]
    preamble = ("set echo off\nset define off\nset verify off\n"
                "whenever oserror exit failure rollback\nwhenever sqlerror exit failure rollback\n"
                f'connect {username}/"{password}"@{alias}\n'
                "begin\n"
                f"  if user <> '{username}' or sys_context('USERENV','CURRENT_SCHEMA') <> '{username}' then\n"
                "    raise_application_error(-20001, 'Scenario schema mismatch');\n"
                "  end if;\nend;\n/\n")
    env = os.environ.copy()
    env["TNS_ADMIN"] = secret["WALLET_PATH"]
    # SQLPATH and startup scripts must not execute unreviewed login.sql files.
    env.pop("SQLPATH", None)
    env.pop("ORACLE_PATH", None)
    with tempfile.TemporaryDirectory(prefix="scenario-sqlcl-") as cwd:
        completed = subprocess.run(["sql", "-S", "-L", "-R", "3", "/nolog"], input=preamble + script + "\nexit\n",
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   env=env, cwd=cwd, check=False)
    sanitized = completed.stdout.replace(password, "[REDACTED]")
    # No connection line belongs in retained output, even if SQLcl echoes it.
    sanitized = re.sub(r"(?im)^.*\bconnect\s+.*$", "[connection omitted]", sanitized)
    output.mkdir(parents=True, exist_ok=True)
    log = output / "sqlcl-output.log"
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW
    with os.fdopen(os.open(log, flags, 0o600), "w", encoding="utf-8") as stream:
        stream.write(sanitized)
    sys.stdout.write(sanitized)
    return completed.returncode or (1 if re.search(r"(?m)^\s*(?:ORA-|SP2-|Error starting at line)", sanitized) else 0)


def main():
    if len(sys.argv) != 3:
        print("Usage: sh scripts/scenario/sqlcl.sh scenario-config.json scenario-results/PREFIX_/run.sql", file=sys.stderr)
        return 2
    try:
        return run(sys.argv[1], sys.argv[2])
    except Refused as error:
        print(str(error), file=sys.stderr)
    except (OSError, ValueError, TypeError):
        # Never print exceptions carrying private contents or subprocess input.
        print("Unable to read the local inputs or launch SQLcl; check paths, permissions and installation.", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
