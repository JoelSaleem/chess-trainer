#!/usr/bin/env python3
"""Package and deploy `brasspawn-board-vision` Lambda + Function URL in eu-central-1."""
import base64
import configparser
import datetime
import hashlib
import hmac
import io
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REGION = os.environ.get("VISION_REGION", "eu-central-1")
NAME = "brasspawn-board-vision"
BUCKET = os.environ.get("VISION_BUCKET", "brasspawn-media")
SSM_PARAM = "/brasspawn/prod/openai_api_key"
ROOT = Path(__file__).resolve().parents[2]


def _creds():
    ak = os.environ.get("AWS_ACCESS_KEY_ID")
    sk = os.environ.get("AWS_SECRET_ACCESS_KEY")
    st = os.environ.get("AWS_SESSION_TOKEN")
    if ak and sk:
        return ak, sk, st
    cp = configparser.ConfigParser()
    cp.read(Path.home() / ".aws/credentials")
    sec = cp[os.environ.get("AWS_PROFILE", "default")]
    return sec["aws_access_key_id"].strip(), sec["aws_secret_access_key"].strip(), sec.get("aws_session_token")


def _sign(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def aws_req(service: str, region: str, method: str, path: str = "/", query: str = "", body: bytes = b"", headers_extra: dict | None = None, host_override: str | None = None):
    ak, sk, st = _creds()
    host = host_override or f"{service}.{region}.amazonaws.com"
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()
    headers = {"host": host, "x-amz-date": amz_date, "x-amz-content-sha256": payload_hash}
    if st:
        headers["x-amz-security-token"] = st
    if headers_extra:
        for k, v in headers_extra.items():
            headers[k.lower()] = v
    sorted_keys = sorted(headers.keys())
    canonical_headers = "".join(f"{k}:{headers[k].strip()}\n" for k in sorted_keys)
    signed_headers = ";".join(sorted_keys)
    canonical_uri = "/".join(urllib.parse.quote(seg, safe="-._~") for seg in path.split("/")) if path else "/"
    canonical_req = "\n".join([method, canonical_uri, query, canonical_headers, signed_headers, payload_hash])
    scope = f"{date_stamp}/{region}/{service}/aws4_request"
    sts = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_req.encode("utf-8")).hexdigest()])
    k_signing = _sign(_sign(_sign(_sign(("AWS4" + sk).encode("utf-8"), date_stamp), region), service), "aws4_request")
    sig = hmac.new(k_signing, sts.encode("utf-8"), hashlib.sha256).hexdigest()
    req_headers = {k: v for k, v in headers.items() if k != "host"}
    req_headers["Authorization"] = f"AWS4-HMAC-SHA256 Credential={ak}/{scope}, SignedHeaders={signed_headers}, Signature={sig}"
    url = f"https://{host}{canonical_uri}" + (f"?{query}" if query else "")
    req = urllib.request.Request(url, data=body if method not in ("GET", "HEAD", "DELETE") or body else None, method=method, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{service} {method} {path} HTTP {e.code}: {e.read().decode('utf-8', errors='replace')[:800]}")


def build_zip_bytes() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("package.json", '{\n  "type": "module"\n}\n')
        for f in ("lambda.mjs", "vision.mjs", "jws.mjs", "quota.mjs"):
            zf.write(ROOT / "scripts/vision" / f, f"scripts/vision/{f}")
        for f in ("s3.mjs", "ssm.mjs"):
            zf.write(ROOT / "scripts/feed" / f, f"scripts/feed/{f}")
        chess_dir = ROOT / "node_modules/chess.js"
        for p in sorted(chess_dir.rglob("*")):
            if p.is_file():
                zf.write(p, str(p.relative_to(ROOT)))
    return buf.getvalue()


def get_account_id() -> str:
    _, xml_text = aws_req("sts", "us-east-1", "POST", "/", body=b"Action=GetCallerIdentity&Version=2011-06-15", headers_extra={"content-type": "application/x-www-form-urlencoded"}, host_override="sts.amazonaws.com")
    root = ET.fromstring(xml_text)
    for el in root.iter():
        if el.tag.endswith("Account"):
            return el.text.strip()
    raise RuntimeError("No Account in STS response")


def iam_form(params: dict) -> str:
    body = urllib.parse.urlencode({"Version": "2010-05-08", **params}).encode("utf-8")
    _, out = aws_req("iam", "us-east-1", "POST", "/", body=body, headers_extra={"content-type": "application/x-www-form-urlencoded"}, host_override="iam.amazonaws.com")
    return out


def lambda_call(method: str, path: str, payload: dict | None = None):
    body = json.dumps(payload).encode("utf-8") if payload is not None else b""
    hdrs = {"content-type": "application/json"} if payload is not None else None
    _, out = aws_req("lambda", REGION, method, path, body=body, headers_extra=hdrs)
    return json.loads(out) if out.strip() else {}


def wait_lambda_ready():
    for _ in range(45):
        cfg = lambda_call("GET", f"/2015-03-31/functions/{NAME}/configuration")
        if cfg.get("State") == "Active" and cfg.get("LastUpdateStatus") in (None, "Successful"):
            return cfg
        time.sleep(2)
    raise TimeoutError("Timed out waiting for Lambda readiness")


def deploy() -> dict:
    zip_bytes = build_zip_bytes()
    print(f"▸ Built {NAME} zip ({len(zip_bytes) / 1024:.1f} KB)")

    account = get_account_id()
    role_arn = f"arn:aws:iam::{account}:role/{NAME}"

    created_role = False
    try:
        iam_form({"Action": "GetRole", "RoleName": NAME})
    except RuntimeError as e:
        if "HTTP 404" not in str(e):
            raise
        print(f"▸ Creating IAM role {NAME}")
        assume_doc = json.dumps({
            "Version": "2012-10-17",
            "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}],
        })
        iam_form({"Action": "CreateRole", "RoleName": NAME, "AssumeRolePolicyDocument": assume_doc})
        created_role = True

    policy_doc = json.dumps({
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "CloudWatchLogs",
                "Effect": "Allow",
                "Action": ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
                "Resource": f"arn:aws:logs:{REGION}:{account}:*",
            },
            {
                "Sid": "OpenAIKeyParameter",
                "Effect": "Allow",
                "Action": ["ssm:GetParameter"],
                "Resource": f"arn:aws:ssm:{REGION}:{account}:parameter{SSM_PARAM}",
            },
            {
                "Sid": "OpenAIKeyKMSDecrypt",
                "Effect": "Allow",
                "Action": ["kms:Decrypt"],
                "Resource": f"arn:aws:kms:{REGION}:{account}:key/*",
                "Condition": {"StringEquals": {"kms:ViaService": f"ssm.{REGION}.amazonaws.com"}},
            },
            {
                "Sid": "VisionQuotaObjects",
                "Effect": "Allow",
                "Action": ["s3:GetObject", "s3:PutObject"],
                "Resource": f"arn:aws:s3:::{BUCKET}/vision-quota/*",
            },
            {
                "Sid": "VisionQuotaBucketList",
                "Effect": "Allow",
                "Action": ["s3:ListBucket"],
                "Resource": f"arn:aws:s3:::{BUCKET}",
            },
        ],
    })
    iam_form({"Action": "PutRolePolicy", "RoleName": NAME, "PolicyName": "vision-least-privilege", "PolicyDocument": policy_doc})
    # Detach managed policy if previously attached so ONLY the explicit policy applies
    try:
        iam_form({"Action": "DetachRolePolicy", "RoleName": NAME, "PolicyArn": "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"})
    except RuntimeError:
        pass

    if created_role:
        print("▸ Waiting 10s for IAM role propagation...")
        time.sleep(10)

    env_vars = {
        "VISION_REGION": REGION,
        "VISION_BUCKET": BUCKET,
        "OPENAI_SSM_PARAM": SSM_PARAM,
    }
    zip_b64 = base64.b64encode(zip_bytes).decode("ascii")

    exists = True
    try:
        lambda_call("GET", f"/2015-03-31/functions/{NAME}/configuration")
    except RuntimeError as e:
        if "HTTP 404" in str(e):
            exists = False
        else:
            raise

    if exists:
        print(f"▸ Updating {NAME} code & configuration")
        lambda_call("PUT", f"/2015-03-31/functions/{NAME}/code", {"ZipFile": zip_b64})
        wait_lambda_ready()
        lambda_call("PUT", f"/2015-03-31/functions/{NAME}/configuration", {
            "Role": role_arn,
            "Runtime": "nodejs22.x",
            "Handler": "scripts/vision/lambda.handler",
            "MemorySize": 1024,
            "Timeout": 60,
            "Environment": {"Variables": env_vars},
        })
        cfg = wait_lambda_ready()
    else:
        print(f"▸ Creating Lambda {NAME}")
        cfg = None
        for attempt in range(8):
            try:
                cfg = lambda_call("POST", "/2015-03-31/functions", {
                    "FunctionName": NAME,
                    "Runtime": "nodejs22.x",
                    "Role": role_arn,
                    "Handler": "scripts/vision/lambda.handler",
                    "Code": {"ZipFile": zip_b64},
                    "Description": "Brass Pawn chessboard vision (gpt-6.1-terra high + StoreKit 2 JWS quota)",
                    "Timeout": 60,
                    "MemorySize": 1024,
                    "Architectures": ["arm64"],
                    "Environment": {"Variables": env_vars},
                })
                break
            except RuntimeError as e:
                if "cannot be assumed" in str(e) and attempt < 7:
                    time.sleep(5)
                    continue
                raise
        cfg = wait_lambda_ready()

    # Cap concurrent executions to protect upstream OpenAI budget against burst spikes
    try:
        lambda_call("PUT", f"/2017-10-31/functions/{NAME}/concurrency", {"ReservedConcurrentExecutions": 10})
    except RuntimeError as e:
        print(f"  (note: concurrency cap skipped: {e})")

    # Public Function URL with CORS * for POST (preflight OPTIONS handled automatically by AWS Function URL CORS)
    cors_cfg = {
        "AllowOrigins": ["*"],
        "AllowMethods": ["POST"],
        "AllowHeaders": ["*"],
        "MaxAge": 86400,
    }
    try:
        lambda_call("GET", f"/2021-10-31/functions/{NAME}/url")
        url_cfg = lambda_call("PUT", f"/2021-10-31/functions/{NAME}/url", {"AuthType": "NONE", "Cors": cors_cfg})
    except RuntimeError as e:
        if "HTTP 404" not in str(e):
            raise
        url_cfg = lambda_call("POST", f"/2021-10-31/functions/{NAME}/url", {"AuthType": "NONE", "Cors": cors_cfg})

    # Public invoke permissions (both InvokeFunctionUrl and InvokeFunction via Function URL)
    for stmt in (
        {"StatementId": "public-function-url", "Action": "lambda:InvokeFunctionUrl", "Principal": "*", "FunctionUrlAuthType": "NONE"},
        {"StatementId": "public-function-url-invoke", "Action": "lambda:InvokeFunction", "Principal": "*", "InvokedViaFunctionUrl": True},
    ):
        try:
            lambda_call("POST", f"/2015-03-31/functions/{NAME}/policy", stmt)
        except RuntimeError as e:
            if "HTTP 409" not in str(e):
                print(f"  (policy note {stmt['StatementId']}: {e})")

    # CloudWatch Log Group with 14-day retention
    for target, payload in (
        ("CreateLogGroup", {"logGroupName": f"/aws/lambda/{NAME}"}),
        ("PutRetentionPolicy", {"logGroupName": f"/aws/lambda/{NAME}", "retentionInDays": 14}),
    ):
        try:
            aws_req(
                "logs",
                REGION,
                "POST",
                "/",
                body=json.dumps(payload).encode("utf-8"),
                headers_extra={"content-type": "application/x-amz-json-1.1", "x-amz-target": f"Logs_20140328.{target}"},
            )
        except RuntimeError as e:
            if "ResourceAlreadyExistsException" not in str(e):
                print(f"  (logs note {target}: {e})")

    fn_url = url_cfg["FunctionUrl"]
    fn_arn = cfg["FunctionArn"]
    print(f"✓ Deployed {fn_arn}")
    print(f"✓ Function URL: {fn_url}")
    return {"functionArn": fn_arn, "functionUrl": fn_url}


if __name__ == "__main__":
    deploy()
