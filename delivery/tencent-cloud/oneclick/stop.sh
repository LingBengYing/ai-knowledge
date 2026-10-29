#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
[ -f .env ] || { echo '尚未初始化。'; exit 0; }
docker compose down --timeout 60
echo '已停止，资料、模型配置和本机密钥保留。'
