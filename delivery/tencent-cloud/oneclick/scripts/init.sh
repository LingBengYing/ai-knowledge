#!/bin/sh
set -eu
umask 077
: "${PUBLIC_HOST:?}" "${PUBLIC_PORT:?}"
mkdir -p /runtime /data /ocr
if [ -f /runtime/ready ]; then
  test "$(cat /runtime/origin)" = "https://${PUBLIC_HOST}:${PUBLIC_PORT}" || { echo '已有地址配置，请勿直接改端口或域名。'; exit 1; }
  test -s /runtime/common.env && test -s /runtime/tls.key && test -s /runtime/tls.crt
  chown -R 10001:10001 /runtime
  chown 10001:10001 /data /ocr
  chmod 700 /runtime /data /ocr
  chmod 600 /runtime/*
  exit 0
fi
# Preserve established credentials if a first run was interrupted before readiness.
if [ -f /runtime/origin ]; then
  test "$(cat /runtime/origin)" = "https://${PUBLIC_HOST}:${PUBLIC_PORT}" || exit 1
fi
if [ ! -f /runtime/common.env ]; then
jwt=$(openssl rand -hex 32)
agent=$(openssl rand -hex 32)
milvus=$(openssl rand -hex 24)
printf 'RAG_JWT_SECRET=%s\nAGENT_SERVICE_TOKEN=%s\nRAG_MILVUS_TOKEN=root:%s\nCOMMON_SECURITY_DEFAULTROOTPASSWORD=%s\n' \
  "$jwt" "$agent" "$milvus" "$milvus" > /runtime/common.env.tmp
mv /runtime/common.env.tmp /runtime/common.env
fi
# Credentials have exact generated format; never source partial or edited shell input.
test "$(wc -l < /runtime/common.env | tr -d ' ')" = 4
grep -Eq '^RAG_JWT_SECRET=[0-9a-f]{64}$' /runtime/common.env
grep -Eq '^AGENT_SERVICE_TOKEN=[0-9a-f]{64}$' /runtime/common.env
grep -Eq '^RAG_MILVUS_TOKEN=root:[0-9a-f]{48}$' /runtime/common.env
grep -Eq '^COMMON_SECURITY_DEFAULTROOTPASSWORD=[0-9a-f]{48}$' /runtime/common.env
printf '%s\n' "https://${PUBLIC_HOST}:${PUBLIC_PORT}" > /runtime/origin
case "$PUBLIC_HOST" in
  *[!0-9.]* ) san="DNS:$PUBLIC_HOST" ;;
  * ) san="IP:$PUBLIC_HOST" ;;
esac
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout /runtime/tls.key -out /runtime/tls.crt -subj "/CN=$PUBLIC_HOST" -addext "subjectAltName=$san,DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
printf '1\n' > /runtime/ready
chown -R 10001:10001 /runtime
chown 10001:10001 /data /ocr
chmod 700 /runtime /data /ocr
chmod 600 /runtime/*
echo '本机配置初始化完成。'
