# -*- coding: utf-8 -*-
"""
gen_cert.py — 自签 CA / server 证书生成器（增量改动，公网 TLS 增强）

功能：
  1. 生成 CA 私钥 + 自签 CA 证书（保存为 ca.crt / ca.key），用于签发 server 证书
  2. 用 CA 签发 server 证书（CN=mcp-android，SAN 含 IP:192.0.2.1 + DNS:mcp.example.com）
  3. server 证书输出到 ai_mcp_server/certs/server.crt + server.key
  4. 把 server 自签证书的公钥（PEM 主体）拷贝到
     ../AiAgentForAndroid/app/src/main/res/raw/server_ca.pem
     作为手机端的「pin CA」——只信任该 CA 签发的证书

幂等：证书/key 已存在则跳过生成；--force 强制重新生成。
依赖：openssl 在 PATH 中（Windows 上 Git for Windows 自带）。
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

# 路径常量（相对 ai_mcp_server/ 目录）
HERE = Path(__file__).resolve().parent
SERVER_DIR = HERE.parent
CERTS_DIR = SERVER_DIR / "certs"
CA_KEY = CERTS_DIR / "ca.key"
CA_CRT = CERTS_DIR / "ca.crt"
SERVER_KEY = CERTS_DIR / "server.key"
SERVER_CRT = CERTS_DIR / "server.crt"

# Android 端：res/raw 下放 server 证书公钥（PEM），用于构造 TrustManager
ANDROID_RAW_DIR = SERVER_DIR.parent / "AiAgentForAndroid" / "app" / "src" / "main" / "res" / "raw"
ANDROID_CA_PEM = ANDROID_RAW_DIR / "server_ca.pem"

# 证书参数
CA_DAYS = 3650            # CA 10 年（root CA，长期保存）
SERVER_DAYS = 825         # server 证书 825 天（公网证书惯例）
CN = "mcp-android"
def _legacy_san():
    """历史/过渡用 SAN —— 不在源码里硬编码公网 IP，需要时用 env 注入。

    例：MCP_LEGACY_SAN="IP:203.0.113.9" python gen_cert.py --force
    （2026-09-14 当前线上证书仍含旧公网 IP 的 SAN，属一次性过渡；
      服务端已只绑 WireGuard 内网，该条 SAN 实际已无连接价值。）
    """
    return [s.strip() for s in os.environ.get("MCP_LEGACY_SAN", "").split(",") if s.strip()]


SAN_ENTRIES = [
    "IP:192.0.2.1",         # ECS WireGuard 内网 IP ← 手机/PC 默认连接目标
    "DNS:mcp.example.com",   # 用户域名（手机端 OkHttp hostnameVerifier 可走 DNS）
    "DNS:localhost",       # 本地测试
    "IP:127.0.0.1",
    "IP:192.0.2.1",    # 本机 Windows 移动热点网关（历史条目，保留以兼容）
] + _legacy_san()


def run(cmd, check=True):
    """运行 openssl 命令并打印。"""
    print(">>", " ".join(str(c) for c in cmd))
    res = subprocess.run(cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        sys.stderr.write(res.stdout)
        sys.stderr.write(res.stderr)
        raise SystemExit(f"openssl 命令失败 (rc={res.returncode}): {' '.join(map(str, cmd))}")
    return res


def generate_ca(force: bool) -> None:
    """生成自签 CA（仅 force 时覆盖；不存在则生成）。"""
    if CA_KEY.exists() and CA_CRT.exists() and not force:
        print(f"[skip] CA 已存在: {CA_KEY}, {CA_CRT}")
        return
    print("[gen] 生成自签 CA ...")
    # 1. 生成 2048 位 RSA 私钥
    run([
        "openssl", "genrsa", "-out", str(CA_KEY), "2048",
    ])
    # 2. 自签 CA 证书
    run([
        "openssl", "req", "-x509", "-new", "-nodes",
        "-key", str(CA_KEY),
        "-sha256", "-days", str(CA_DAYS),
        "-subj", "/C=CN/ST=Public/L=Internet/O=mcp-for-android/CN=mcp-android-CA",
        "-out", str(CA_CRT),
    ])


def generate_server(force: bool) -> None:
    """用 CA 签发 server 证书。"""
    if SERVER_KEY.exists() and SERVER_CRT.exists() and not force:
        print(f"[skip] server 证书已存在: {SERVER_KEY}, {SERVER_CRT}")
        return

    print("[gen] 生成 server 私钥 + CSR ...")
    run([
        "openssl", "genrsa", "-out", str(SERVER_KEY), "2048",
    ])

    san_ext = ",".join(SAN_ENTRIES)
    csr_subject = f"/C=CN/ST=Public/L=Internet/O=mcp-for-android/CN={CN}"

    # CSR
    csr_conf = CERTS_DIR / "_csr.cnf"
    csr_conf.write_text(
        f"[req]\n"
        f"distinguished_name = req_dn\n"
        f"req_extensions = v3_req\n"
        f"prompt = no\n"
        f"[req_dn]\n"
        f"CN = {CN}\n"
        f"[v3_req]\n"
        f"subjectAltName = {san_ext}\n"
        f"keyUsage = critical, digitalSignature, keyEncipherment\n"
        f"extendedKeyUsage = serverAuth\n",
        encoding="utf-8",
    )
    run([
        "openssl", "req", "-new",
        "-key", str(SERVER_KEY),
        "-out", str(CERTS_DIR / "server.csr"),
        "-config", str(csr_conf),
    ])

    # 用 CA 签发 server 证书（含 SAN）
    ext_conf = CERTS_DIR / "_v3.ext"
    ext_conf.write_text(
        f"authorityKeyIdentifier=keyid,issuer\n"
        f"basicConstraints=CA:FALSE\n"
        f"keyUsage = critical, digitalSignature, keyEncipherment\n"
        f"extendedKeyUsage = serverAuth\n"
        f"subjectAltName = {san_ext}\n",
        encoding="utf-8",
    )
    run([
        "openssl", "x509", "-req",
        "-in", str(CERTS_DIR / "server.csr"),
        "-CA", str(CA_CRT),
        "-CAkey", str(CA_KEY),
        "-CAcreateserial",
        "-out", str(SERVER_CRT),
        "-days", str(SERVER_DAYS),
        "-sha256",
        "-extfile", str(ext_conf),
    ])

    # 清理临时文件
    for tmp in [CERTS_DIR / "server.csr", csr_conf, ext_conf, CERTS_DIR / "ca.srl"]:
        if tmp.exists():
            tmp.unlink()


def copy_ca_to_android() -> None:
    """把 **CA 证书**（PEM 主体）拷贝到 Android res/raw/server_ca.pem。

    注意：手机端 MCPTls 把该文件当作 TrustManager 的信任锚（pin CA），
    必须放 CA 证书而不是 server 叶子证书 —— 放叶子证书会导致换发 server 证书后
    手机立刻握手失败（此处历史上曾误拷 SERVER_CRT，2026-09-14 切内网时修正）。
    """
    ANDROID_RAW_DIR.mkdir(parents=True, exist_ok=True)
    src = CA_CRT.read_text(encoding="utf-8")
    ANDROID_CA_PEM.write_text(src, encoding="utf-8")
    print(f"[copy] {CA_CRT} -> {ANDROID_CA_PEM}")


def main():
    parser = argparse.ArgumentParser(description="生成 mcp_for_android 公网 TLS 自签证书")
    parser.add_argument("--force", action="store_true", help="强制重新生成所有证书")
    args = parser.parse_args()

    CERTS_DIR.mkdir(parents=True, exist_ok=True)
    if not shutil.which("openssl"):
        sys.stderr.write("错误: 未在 PATH 中找到 openssl，请先安装 OpenSSL。\n")
        return 1

    generate_ca(args.force)
    generate_server(args.force)
    copy_ca_to_android()

    print()
    print("=== 证书生成完成 ===")
    print(f"  CA:        {CA_CRT}")
    print(f"  server:    {SERVER_CRT}")
    print(f"  Android:   {ANDROID_CA_PEM}")
    print(f"  SAN:       {', '.join(SAN_ENTRIES)}")
    print(f"  有效期:    {SERVER_DAYS} 天")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
