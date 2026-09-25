#!/usr/bin/env python3
"""为 /api/bs/app/latest 生成 Ed25519 分离签名（beike-update-v2）。

服务端把下列字段的**规范化 JSON** 用 Ed25519 签名，把 Base64(sig) 放进响应 `sig` 字段。
App 侧见 `data/remote/UpdateSignature.kt`：无签名/验签失败会**完全忽略自有更新源**。

规范化 JSON（必须逐字一致）：
  - 紧凑分隔符 ',' ':'，无空格
  - 键序固定：versionCode, versionName, changelog, force, size, url, apkSha256
  - 不含 sig 字段
  - UTF-8；布尔为 true/false；changelog 原样（JSON 转义）
  - apkSha256 = APK 文件的 SHA-256（hex 小写）；**v2 起必填**——签名覆盖 APK 本体，
    明文 HTTP 上下载包被替换时客户端摘要校验会拒绝安装。

  {"versionCode":44,"versionName":"1.3.3","changelog":"...","force":false,"size":123,
   "url":"http://...","apkSha256":"<64 hex>"}

用法：
  python3 tools/sign_update.py --key keystore/update_signing_private.pem \\
      --version-code 44 --version-name 1.3.3 --changelog "fix" \\
      --url "http://112.125.88.178/apk/BeikeSchedule-1.3.3.apk" \\
      --apk BeikeSchedule-1.3.3-release.apk [--force]

  --apk 传入 APK 文件时自动计算 size 与 apkSha256；也可用 --size/--apk-sha256 手动指定。
输出：带 sig 的完整 JSON，可直接作为 GET /api/bs/app/latest 的响应体。
私钥：OpenSSL Ed25519 PEM（gitignore 的 keystore/update_signing_private.pem）。不要提交私钥。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

KEY_ORDER = ["versionCode", "versionName", "changelog", "force", "size", "url", "apkSha256"]


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
    p.add_argument("--url", required=True, help="APK 下载地址（直链 .apk）")
    p.add_argument("--apk", type=Path, help="APK 文件：自动计算 size 与 apkSha256")
    p.add_argument("--size", type=int, default=None, help="APK 字节数（给了 --apk 可省略）")
    p.add_argument("--apk-sha256", default=None, help="APK SHA-256 hex（给了 --apk 可省略）")
    p.add_argument("--force", action="store_true", help="强制更新")
    args = p.parse_args()

    size = args.size
    sha256 = args.apk_sha256
    if args.apk is not None:
        data = args.apk.read_bytes()
        if size is None:
            size = len(data)
        if sha256 is None:
            sha256 = hashlib.sha256(data).hexdigest()
    if size is None:
        p.error("--size 或 --apk 必须提供一个")
    if sha256 is None:
        # v1 兼容：旧客户端也按含 apkSha256 的 7 键格式验签（空串同样在签名内），
        # 但强烈建议始终用 --apk 生成，让签名覆盖 APK 本体
        sha256 = ""

    body = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "changelog": args.changelog,
        "force": bool(args.force),
        "size": int(size),
        "url": args.url,
        "apkSha256": sha256.lower(),
    }
    message = canonical_bytes(body)
    signature = sign_openssl(args.key, message)
    # 输出响应体：键序保持业务字段 + sig（apkSha256 空串时省略，客户端按旧约定兼容）
    out = {k: body[k] for k in KEY_ORDER if body[k] != ""}
    out["sig"] = base64.b64encode(signature).decode("ascii")
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
