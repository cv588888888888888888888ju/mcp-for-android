# -*- coding: utf-8 -*-
"""
resign_server_cert.py — 复用现有 CA 重签 server 证书（切 WireGuard 内网，2026-09-14）

为什么需要它：
  `gen_cert.py` 依赖 PATH 里的 openssl；在受限环境下（MSYS/Cygwin 无法建命名管道）
  openssl 直接不可用，而且 `gen_cert.py --force` 会连带重生成 CA —— 那会让 iPad
  等已 pin 该 CA 的客户端全部失效。

本脚本的契约（安全边界）：
  * **绝不** 动 ca.key / ca.crt —— CA 保持不变，已 pin CA 的客户端（手机 App 的
    res/raw/server_ca.pem、iPad）继续可用。
  * 默认复用现有 certs/server.key（只换证书，不换私钥，分发面最小）。
  * 只重写 certs/server.crt，并把 **CA 证书** 同步到 res/raw/server_ca.pem。
  * 纯 Python（cryptography 包），不依赖 openssl 可执行文件。

用法：
    python resign_server_cert.py                 # 用内置默认 SAN 重签
    python resign_server_cert.py --add IP:192.0.2.10
    python resign_server_cert.py --new-key       # 连私钥一起换（需同步分发 server.key）
    python resign_server_cert.py --days 825
"""
from __future__ import annotations

import argparse
import datetime as _dt
import ipaddress
import shutil
import sys
from pathlib import Path

try:
    from cryptography import x509
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
except ImportError:  # pragma: no cover
    sys.stderr.write(
        "错误: 未安装 cryptography 包。请运行: pip install cryptography\n"
    )
    raise SystemExit(1)

# cryptography < 3.1 要求显式传 backend=；>= 3.1 已弃用该参数。
# 这里做兼容：先试新式调用，失败再退回带 backend 的旧式调用。
_BACKEND = default_backend()


def _load_pem_key(data: bytes):
    try:
        return serialization.load_pem_private_key(data, password=None)
    except TypeError:
        return serialization.load_pem_private_key(data, password=None, backend=_BACKEND)


def _load_pem_cert(data: bytes):
    try:
        return x509.load_pem_x509_certificate(data)
    except TypeError:
        return x509.load_pem_x509_certificate(data, backend=_BACKEND)

HERE = Path(__file__).resolve().parent
SERVER_DIR = HERE.parent
CERTS_DIR = SERVER_DIR / "certs"
CA_KEY = CERTS_DIR / "ca.key"
CA_CRT = CERTS_DIR / "ca.crt"
SERVER_KEY = CERTS_DIR / "server.key"
SERVER_CRT = CERTS_DIR / "server.crt"

ANDROID_RAW_DIR = (
    SERVER_DIR.parent / "AiAgentForAndroid" / "app" / "src" / "main" / "res" / "raw"
)
ANDROID_CA_PEM = ANDROID_RAW_DIR / "server_ca.pem"

CN = "mcp-android"
SERVER_DAYS = 825

# 默认 SAN：192.0.2.1 置首（新的默认连接目标=ECS WireGuard 内网），
# 其余为历史条目，保留以平滑过渡（旧客户端 / iPad / 本机热点调试都不炸）。
# 旧公网 IP 等过渡条目不在源码里硬编码，用 env MCP_LEGACY_SAN 注入，例如：
#   MCP_LEGACY_SAN="IP:203.0.113.9" python resign_server_cert.py
# （过渡期可保留一条旧公网 IP 的 SAN，用 MCP_LEGACY_SAN 注入。）
DEFAULT_SAN = [
    "IP:192.0.2.1",         # ECS WireGuard 内网 IP ← 手机/PC 默认连接目标
    "DNS:mcp.example.com",
    "DNS:localhost",
    "IP:127.0.0.1",
    "IP:192.0.2.1",    # 本机 Windows 移动热点网关（历史条目）
]


def _legacy_san():
    import os

    return [s.strip() for s in os.environ.get("MCP_LEGACY_SAN", "").split(",") if s.strip()]


def _load_ca():
    if not CA_KEY.exists() or not CA_CRT.exists():
        raise SystemExit(f"错误: 找不到 CA 材料 {CA_KEY} / {CA_CRT}")
    ca_key = _load_pem_key(CA_KEY.read_bytes())
    ca_crt = _load_pem_cert(CA_CRT.read_bytes())
    return ca_key, ca_crt


def _load_or_make_server_key(new_key: bool):
    if SERVER_KEY.exists() and not new_key:
        key = _load_pem_key(SERVER_KEY.read_bytes())
        print(f"[key] 复用现有私钥 {SERVER_KEY}")
        return key, False
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    print("[key] 生成新私钥（需同步分发 server.key 到服务端）")
    return key, True


def _build_san(entries):
    names = []
    for raw in entries:
        if ":" not in raw:
            raise SystemExit(f"错误: SAN 条目格式应为 'IP:x.x.x.x' 或 'DNS:name'，收到 {raw!r}")
        kind, _, value = raw.partition(":")
        kind = kind.strip().upper()
        value = value.strip()
        if kind == "IP":
            names.append(x509.IPAddress(ipaddress.ip_address(value)))
        elif kind == "DNS":
            names.append(x509.DNSName(value))
        else:
            raise SystemExit(f"错误: 不支持的 SAN 类型 {kind!r}（只支持 IP / DNS）")
    return x509.SubjectAlternativeName(names)


def resign(san_entries, days: int, new_key: bool) -> int:
    ca_key, ca_crt = _load_ca()
    server_key, key_is_new = _load_or_make_server_key(new_key)

    now = _dt.datetime.now(_dt.timezone.utc)
    builder = (
        x509.CertificateBuilder()
        .subject_name(
            x509.Name(
                [
                    x509.NameAttribute(NameOID.COUNTRY_NAME, "CN"),
                    x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, "Public"),
                    x509.NameAttribute(NameOID.LOCALITY_NAME, "Internet"),
                    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "mcp-for-android"),
                    x509.NameAttribute(NameOID.COMMON_NAME, CN),
                ]
            )
        )
        .issuer_name(ca_crt.subject)
        .public_key(server_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - _dt.timedelta(days=1))   # 容忍客户端时钟偏差
        .not_valid_after(now + _dt.timedelta(days=days))
        .add_extension(_build_san(san_entries), critical=False)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=True,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(
            x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False
        )
        .add_extension(
            x509.BasicConstraints(ca=False, path_length=None), critical=True
        )
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(server_key.public_key()),
            critical=False,
        )
        .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_crt.public_key()),
            critical=False,
        )
    )

    # CA 私钥签名（CA 本身不变）
    try:
        cert = builder.sign(private_key=ca_key, algorithm=hashes.SHA256())
    except TypeError:
        cert = builder.sign(
            private_key=ca_key, algorithm=hashes.SHA256(), backend=_BACKEND
        )

    CERTS_DIR.mkdir(parents=True, exist_ok=True)
    if key_is_new:
        SERVER_KEY.write_bytes(
            server_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.TraditionalOpenSSL,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )
        print(f"[write] {SERVER_KEY}")
    SERVER_CRT.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(f"[write] {SERVER_CRT}")

    # 同步 CA 证书（不是叶子证书！）到 Android res/raw
    ANDROID_RAW_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(CA_CRT, ANDROID_CA_PEM)
    print(f"[copy]  {CA_CRT} -> {ANDROID_CA_PEM}")

    fp = cert.fingerprint(hashes.SHA256()).hex().upper()
    fp = ":".join(fp[i : i + 2] for i in range(0, len(fp), 2))
    print()
    print("=== 重签完成（CA 未改动）===")
    print(f"  subject : {cert.subject.rfc4514_string()}")
    print(f"  issuer  : {cert.issuer.rfc4514_string()}")
    print(f"  serial  : {cert.serial_number}")
    print(f"  notBefore: {cert.not_valid_before_utc if hasattr(cert,'not_valid_before_utc') else cert.not_valid_before} UTC")
    print(f"  notAfter : {cert.not_valid_after_utc if hasattr(cert,'not_valid_after_utc') else cert.not_valid_after} UTC")
    print(f"  SHA256  : {fp}")
    print("  SAN     :")
    for name in cert.extensions.get_extension_for_class(
        x509.SubjectAlternativeName
    ).value:
        print(f"      {name}")
    print()
    print("下一步：把 certs/server.crt 分发到 ECS /opt/mcp_for_android/ai_mcp_server/certs/"
          + ("，并一并分发 server.key" if key_is_new else "（私钥未变，无需分发 server.key）"))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="复用现有 CA 重签 server 证书（不动 CA）")
    ap.add_argument(
        "--san",
        action="append",
        default=None,
        help="覆盖默认 SAN；可重复。例：--san IP:192.0.2.1 --san DNS:foo",
    )
    ap.add_argument("--days", type=int, default=SERVER_DAYS, help=f"有效期天数（默认 {SERVER_DAYS}）")
    ap.add_argument("--new-key", action="store_true", help="同时重新生成 server 私钥")
    args = ap.parse_args()

    san_entries = args.san if args.san else list(DEFAULT_SAN) + _legacy_san()
    return resign(san_entries, args.days, args.new_key)


if __name__ == "__main__":
    raise SystemExit(main())
