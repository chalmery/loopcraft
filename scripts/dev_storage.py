"""Minimal signed S3 requests for local development bucket preparation."""

from datetime import datetime, timezone
import hashlib
import hmac
import re
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Signed credentials are scoped to the explicitly configured endpoint.
        return None


def s3_request(env, method, key="", payload=b"", now=None):
    endpoint = urlsplit(env["STORAGE_ENDPOINT"])
    if endpoint.scheme not in ("http", "https") or not endpoint.netloc or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path not in ("", "/"):
        raise ValueError("STORAGE_ENDPOINT must be an HTTP(S) origin without credentials or a path")
    bucket = env["SKILL_BUCKET"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket):
        raise ValueError("SKILL_BUCKET must be a valid S3 bucket name")
    path = "/" + quote(bucket + ("/" + key if key else ""), safe="/-._~")
    date = (now or datetime.now(timezone.utc)).strftime("%Y%m%dT%H%M%SZ")
    region = env["STORAGE_REGION"]
    digest = hashlib.sha256(payload).hexdigest()
    headers = {"host": endpoint.netloc, "x-amz-content-sha256": digest, "x-amz-date": date}
    signed = ";".join(headers)
    canonical = "\n".join([method, path, "", "".join(f"{k}:{v}\n" for k, v in headers.items()), signed, digest])
    scope = date[:8] + "/" + region + "/s3/aws4_request"
    message = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + hashlib.sha256(canonical.encode()).hexdigest()
    signing = ("AWS4" + env["STORAGE_SECRET_KEY"]).encode()
    for part in (date[:8], region, "s3", "aws4_request"):
        signing = hmac.new(signing, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(signing, message.encode(), hashlib.sha256).hexdigest()
    headers["Authorization"] = f"AWS4-HMAC-SHA256 Credential={env['STORAGE_ACCESS_KEY']}/{scope}, SignedHeaders={signed}, Signature={signature}"
    request = Request(env["STORAGE_ENDPOINT"].rstrip("/") + path, method=method,
                      data=payload if method in ("PUT", "POST") else None, headers=headers)
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=5) as response:
            return response.status, response.read()
    except HTTPError as error:
        with error:
            return error.code, error.read()
    except (URLError, TimeoutError, ConnectionError):
        raise ValueError("File storage is unreachable; check RustFS and STORAGE_ENDPOINT") from None


def prepare_bucket(env, create=True):
    status, _ = s3_request(env, "HEAD")
    if status == 404 and create:
        # RustFS uses the configured region; the default region needs no XML body.
        from xml.sax.saxutils import escape

        payload = b"" if env["STORAGE_REGION"] == "us-east-1" else (
            '<CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
            f'<LocationConstraint>{escape(env["STORAGE_REGION"])}</LocationConstraint>'
            '</CreateBucketConfiguration>').encode()
        created, _ = s3_request(env, "PUT", payload=payload)
        if created not in (200, 204, 409):
            raise ValueError(f"Cannot create Skill bucket (HTTP {created}); check storage credentials and permissions")
        status, _ = s3_request(env, "HEAD")
    if status != 200:
        raise ValueError(f"Skill bucket is unavailable (HTTP {status}); check bucket name, credentials and permissions")
