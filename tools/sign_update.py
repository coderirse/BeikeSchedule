#!/usr/bin/env python3
"""为 /api/bs/app/latest 生成 Ed25519 分离签名（beike-update-v1）。

服务端把下列字段的**规范化 JSON** 用 Ed25519 签名，把 Base64(sig) 放进响应 `sig` 字段。
App 侧见 `data/remote/UpdateSignature.kt`：无签名/验签失败会**完全忽略自有更新源**。

规范化 JSON（必须逐字一致）：
  - 紧凑分隔符 ',' ':'，无空格
  - 键序固定：versionCode, versionName, changelog, force, size, url
  - 不含 sig 字段
  - UTF-8；布尔为 true/false；changelog 原样（JSON 转义）

  {"versionCode":42,"versionName":"1.3.1","changelog":"...","force":false,"size":123,"url":"http://..."}

用法：
  python3 tools/sign_update.py --key keystore/update_signing_private.pem \\
      --version-code 42 --version-name 1.3.1 --changelog "fix" \\
      --size 12345678 --url "http://112.125.88.178/apk/BeikeSchedule-1.3.1.apk" [--force]

输出：带 sig 的完整 JSON，可直接作为 GET /api/bs/app/latest 的响应体。
私钥：OpenSSL Ed25519 PEM（gitignore 的 keystore/update_signing_private.pem）。不要提交私钥。
"""

from __future__ import annotations

import argparse
import base64
import json
import subprocess
import sys
import tempfile
from pathlib import Path

KEY_ORDER = ["versionCode", "versionName", "changelog", "force", "size", "url"]


def canonical_bytes(body: dict) -> bytes:
    ordered = {k: body[k] for k in KEY_ORDER}
    # 与 kotlinx.serialization 紧凑 JSON 对齐：无空格、非 ASCII 不转义
    text = json.dumps(ordered, ensure_ascii=False, separators=(",", ":"))
    return text.encode("utf-8")


def sign_openssl(key_path: Path, message: bytes) -> bytes:
    """OpenSSL 3.x raw Ed25519 签名（不依赖 cryptography 包）。"""
    with tempfile.NamedTemporaryFile(delete=False) as msg_file:
        msg_file.write(message)
        msg_path = msg_file.name
    sig_path = msg_path + ".sig"
    try:
        subprocess.check_call(
            [
                "openssl",
                "pkeyutl",
                "-sign",
                "-inkey",
                str(key_path),
                "-rawin",
                "-in",
                msg_path,
                "-out",
                sig_path,
            ]
        )
        return Path(sig_path).read_bytes()
    finally:
        Path(msg_path).unlink(missing_ok=True)
        Path(sig_path).unlink(missing_ok=True)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--key", required=True, type=Path, help="Ed25519 私钥 PEM")
    p.add_argument("--version-code", required=True, type=int)
    p.add_argument("--version-name", required=True)
    p.add_argument("--changelog", default="")
    p.add_argument("--size", type=int, default=0, help="APK 字节数")
    p.add_argument("--url", required=True, help="APK 下载地址")
    p.add_argument("--force", action="store_true", help="强制更新")
    args = p.parse_args()

    body = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "changelog": args.changelog,
        "force": bool(args.force),
        "size": int(args.size),
        "url": args.url,
    }
    message = canonical_bytes(body)
    signature = sign_openssl(args.key, message)
    body["sig"] = base64.b64encode(signature).decode("ascii")
    # 输出响应体：键序保持业务字段 + sig
    out = {k: body[k] for k in KEY_ORDER}
    out["sig"] = body["sig"]
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
