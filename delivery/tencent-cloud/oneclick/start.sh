#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
umask 077
fail() { printf '\n启动未完成：%s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || fail '请先安装并启动 Docker（含 Compose）。'
docker info >/dev/null 2>&1 || fail 'Docker 尚未运行或当前用户无权访问，请先启动 Docker。'
docker compose version >/dev/null 2>&1 || fail '需要 Docker Compose v2。'
engine_version=$(docker version --format '{{.Server.Version}}')
engine_major=${engine_version%%.*}
case "$engine_major" in *[!0-9]*|'') fail '无法识别 Docker Engine 版本。';; esac
[ "$engine_major" -ge 28 ] || fail '需要 Docker Engine 28 或以上版本，请先更新 Docker。'
if [ ! -f .env ]; then
  port=${KB_PORT:-18443}
  case "$port" in *[!0-9]*|'') fail 'KB_PORT 必须为端口数字。';; esac
  [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || fail '端口必须在 1024–65535。'
  project="knowledge-$(pwd | cksum | cut -d ' ' -f 1)"
  printf 'KB_PROJECT=%s\nKB_HOST=knowledge.localhost\nKB_PORT=%s\n' "$project" "$port" > .env.tmp
  mv .env.tmp .env
fi
# This file only contains generated non-secret identifiers; reject edited shell syntax.
[ "$(wc -l < .env | tr -d ' ')" = 3 ] || fail '.env 配置行数不正确。'
grep -Eq '^KB_PROJECT=knowledge-[0-9]+$' .env || fail '项目名配置无效。'
grep -qx 'KB_HOST=knowledge.localhost' .env || fail '默认启动器仅支持本机地址，外部部署需单独配置。'
grep -Eq '^KB_PORT=[0-9]{4,5}$' .env || fail '端口配置无效。'
set -a
. ./.env
set +a
[ "$KB_PORT" -ge 1024 ] && [ "$KB_PORT" -le 65535 ] || fail '端口超出范围。'
if ! mkdir .startup-lock 2>/dev/null; then fail '另一次启动尚在执行。如之前异常退出，确认无启动进程后删除 .startup-lock 目录。'; fi
trap 'rmdir .startup-lock 2>/dev/null || true' EXIT
trap 'exit 130' HUP INT TERM
missing=0
image_matches() {
  actual=$(docker image inspect --platform linux/amd64 --format '{{.Id}} {{.Os}}/{{.Architecture}}' "$1" 2>/dev/null || true)
  # Classic image stores identify the config, containerd stores the platform manifest.
  [ "$actual" = "$2 linux/amd64" ] || [ "$actual" = "$3 linux/amd64" ]
}
while read -r image expected config; do
  image_matches "$image" "$expected" "$config" || missing=1
done < images.lock
if [ "$missing" = 1 ]; then
  [ -f images.tar ] || fail '缺少离线镜像 images.tar，请解压完整安装包。'
  echo '首次启动：校验并导入离线镜像，无需仓库密码……'
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -c images.sha256; else shasum -a 256 -c images.sha256; fi
  docker load -i images.tar || fail '离线镜像导入失败。'
fi
while read -r image expected config; do
  image_matches "$image" "$expected" "$config" || fail "镜像或平台不匹配：$image；需要 Docker Engine 28+。"
done < images.lock
# No registry login, pull, prune, or volume deletion.
docker compose config --quiet
mkdir -p logs
printf '初始化本机配置与数据卷……\n'
docker compose --profile setup run --rm --no-deps --pull never init > logs/init.log 2>&1 || fail '初始化失败，详情见 logs/init.log。'
printf '启动知识库，首次启动需要等待数据库就绪……\n'
docker compose up -d --pull never --wait --wait-timeout 420 || {
  docker compose ps > logs/status.log
  fail '部分服务尚未就绪，运行 status.sh 可查看状态；数据和配置已保留。'
}
docker compose exec -T gateway node /installer/check.mjs
printf '\n知识库已启动：https://%s:%s/\n' "$KB_HOST" "$KB_PORT"
printf '首次浏览器访问使用本机生成的自签证书，可能需要手动确认。模型密钥在“模型设置”中配置一次。\n'
printf '以后再次运行本文件即可；停止请运行 stop.sh（不会删除资料）。\n'
if [ "${KB_NO_BROWSER:-0}" != 1 ]; then
  # Only macOS open(1) opens URLs; on Debian/Ubuntu `open` is openvt. Never fail a started install over the browser.
  if [ "$(uname -s)" = Darwin ]; then open "https://$KB_HOST:$KB_PORT/" || true; elif command -v xdg-open >/dev/null 2>&1; then xdg-open "https://$KB_HOST:$KB_PORT/" >/dev/null 2>&1 || true; fi
fi
