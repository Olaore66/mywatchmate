#!/bin/bash
# Runs ON the staging server (sent there by deploy/release.sh). Starts the given image version:
#   1. secrets from Parameter Store → /opt/mywatchmate/app.env (readable by root only)
#   2. pull the image from ECR
#   3. replace the app container; start Caddy (HTTPS) if it isn't running
#   4. wait until the app reports healthy
# Usage: deploy.sh <image-uri> <tag> <site-address> <parameter-path> <region>
set -euo pipefail

IMAGE="$1"; TAG="$2"; SITE_ADDRESS="$3"; PARAM_PATH="$4"; REGION="$5"
DIR=/opt/mywatchmate
mkdir -p "$DIR"
cd "$DIR"

echo "== loading configuration from $PARAM_PATH"
aws ssm get-parameters-by-path --region "$REGION" --path "$PARAM_PATH" --with-decryption \
    --query 'Parameters[].[Name,Value]' --output text \
  | while IFS=$'\t' read -r name value; do echo "${name##*/}=${value}"; done > app.env.tmp
chmod 600 app.env.tmp
mv app.env.tmp app.env
echo "   $(wc -l < app.env) settings"

echo "== pulling $IMAGE:$TAG"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${IMAGE%%/*}" >/dev/null
docker pull -q "$IMAGE:$TAG"

docker network inspect mywatchmate >/dev/null 2>&1 || docker network create mywatchmate >/dev/null

echo "== starting app"
docker rm -f app >/dev/null 2>&1 || true
docker run -d --name app --network mywatchmate --restart unless-stopped \
    --env-file app.env -e SERVER_PORT=8081 \
    --log-opt max-size=20m --log-opt max-file=5 \
    "$IMAGE:$TAG" >/dev/null

if ! docker ps --format '{{.Names}}' | grep -qx caddy; then
    echo "== starting caddy for https://$SITE_ADDRESS"
    docker rm -f caddy >/dev/null 2>&1 || true
    docker run -d --name caddy --network mywatchmate --restart unless-stopped \
        -p 80:80 -p 443:443 -e SITE_ADDRESS="$SITE_ADDRESS" \
        -v "$DIR/Caddyfile:/etc/caddy/Caddyfile:ro" -v caddy_data:/data -v caddy_config:/config \
        caddy:2.8.4 >/dev/null
fi

echo "== waiting for the app to become healthy"
for i in $(seq 1 60); do
    if docker exec caddy wget -qO- http://app:8081/actuator/health 2>/dev/null | grep -q '"UP"'; then
        echo "   healthy after $((i * 3))s"
        docker image prune -f >/dev/null 2>&1 || true
        exit 0
    fi
    sleep 3
done
echo "!! app did not become healthy; last log lines:"
docker logs --tail 60 app
exit 1
