#!/usr/bin/env bash
# 一键部署 BeikeSchedule 服务端到阿里云 ECS（依赖 workbench CLI）
# 用法: bash server/scripts/deploy.sh
set -euo pipefail

INSTANCE_ID="i-2ze8wr7at7dgnqy5jgef"
REGION="cn-beijing"
REMOTE_DIR="/opt/beike"
SERVICE="beike"
HEALTH="http://127.0.0.1:3001/health"

WB="$(command -v workbench || echo "${LOCALAPPDATA}/Programs/workbench/workbench.exe")"

cd "$(dirname "$0")/.."

# 只发布代码与依赖；.env、data/、public/downloads 是服务器持久状态，不解压覆盖
echo "[1/4] 打包（src + package.json + node_modules）..."
TMPDIR_LOCAL="$(mktemp -d)"
TARBALL="$TMPDIR_LOCAL/beike-server.tar.gz"
tar czf "$TARBALL" src package.json node_modules

echo "[2/4] 上传（经 OSS 中转）..."
LOCAL_PATH="$TARBALL"
command -v cygpath >/dev/null && LOCAL_PATH="$(cygpath -w "$TARBALL")"
MSYS_NO_PATHCONV=1 "$WB" upload "$LOCAL_PATH" /tmp/ --instance-id "$INSTANCE_ID" --region "$REGION" -f

echo "[3/4] 服务器解压（先删旧 src，避免残留模块；私钥从 secrets/ 恢复）..."
MSYS_NO_PATHCONV=1 "$WB" exec --instance-id "$INSTANCE_ID" --region "$REGION" \
  --command "mkdir -p $REMOTE_DIR && rm -rf $REMOTE_DIR/src && tar -xzf /tmp/beike-server.tar.gz -C $REMOTE_DIR && rm -f /tmp/beike-server.tar.gz && for f in $REMOTE_DIR/secrets/*.pem; do [ -f \"\$f\" ] && cp -f \"\$f\" $REMOTE_DIR/src/; done" \
  --timeout 120

echo "[4/4] 重启服务并健康检查..."
APK_URL=$(node -e "console.log(require('./src/bs-app-version.json').url || '')" 2>/dev/null || true)
WARM_URL=""
case "$APK_URL" in
  http*) WARM_URL="$APK_URL" ;;
  /*) WARM_URL="https://api.caeamer.com$APK_URL" ;;
esac
WARM_REMOTE=""
[ -n "$WARM_URL" ] && WARM_REMOTE="nohup curl -s -o /dev/null '$WARM_URL' >/dev/null 2>&1 &"
MSYS_NO_PATHCONV=1 "$WB" exec --instance-id "$INSTANCE_ID" --region "$REGION" \
  --command "systemctl restart $SERVICE && sleep 2 && systemctl is-active $SERVICE && curl -s $HEALTH && $WARM_REMOTE" \
  --timeout 90 || true

echo "[warmup] 从本机预热 CF 边缘缓存（新 APK）..."
if [ -n "$WARM_URL" ]; then
  curl -s -o /dev/null -w "  $WARM_URL -> HTTP %{http_code}, %{time_total}s\n" "$WARM_URL" || true
fi

echo ""
echo "部署完成"
