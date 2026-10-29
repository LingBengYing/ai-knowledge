#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
umask 077
registry=ccr.ccs.tencentyun.com
login_attempted=0
mkdir -p logs
fail() { printf '%s\n' "$*" >&2; exit 1; }
matches() {
  actual=$(docker image inspect --platform linux/amd64 --format '{{.Id}} {{.Os}}/{{.Architecture}}' "$1" 2>/dev/null || true)
  [ "$actual" = "$2 linux/amd64" ] || [ "$actual" = "$3 linux/amd64" ]
}
while read -r image manifest config <&3; do
  matches "$image" "$manifest" "$config" && continue
  repository=${image%:*}
  case "$repository" in "$registry"/lingbenying-knowledge/knowledge-*) ;; *) fail '镜像清单中的仓库不正确。';; esac
  printf '下载缺少的镜像：%s\n' "$image"
  if ! docker pull --platform linux/amd64 "$repository@$manifest" > logs/image-pull.log 2>&1; then
    if ! grep -Eiq 'unauthorized|authentication required|no basic auth credentials|denied:|pull access denied' logs/image-pull.log; then
      fail '镜像下载失败，详情见 logs/image-pull.log；请检查网络或仓库可用性后重新启动。'
    fi
    [ "$login_attempted" = 0 ] || fail '仓库仍拒绝访问，请核对账号权限；本次不再重复登录。'
    [ -t 0 ] || fail '私有仓库需要首次登录。请双击启动.command，或在交互终端运行 ./start.sh。'
    printf '首次使用需要登录腾讯云私有镜像仓库。密码由 Docker 接收并交给本机凭据存储，启动包不保存密码。\n'
    docker login "$registry" --username "${KB_REGISTRY_USER:-100003738785}" || fail '登录未完成，资料和配置已保留。'
    login_attempted=1
    docker pull --platform linux/amd64 "$repository@$manifest" > logs/image-pull.log 2>&1 || fail '登录后下载仍失败，详情见 logs/image-pull.log；本次停止。'
  fi
  matches "$repository@$manifest" "$manifest" "$config" || fail "下载镜像的身份或架构不匹配：$image"
  docker tag "$repository@$manifest" "$image"
  matches "$image" "$manifest" "$config" || fail "本地镜像校验失败：$image"
done 3< images.lock
printf '所需镜像已准备好。\n'
