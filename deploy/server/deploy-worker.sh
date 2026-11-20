#!/bin/bash
# Runs ON the staging server (sent there by deploy/release-worker.sh). Starts the given cue worker version
# next to the app, on the same docker network, so it talks to the app directly (http://app:8081).
#   1. its one secret, INTERNAL_CUE_TOKEN, from Parameter Store -> /opt/mywatchmate/worker.env (root only)
#      (the worker never sees the database password, JWT secret or any other app setting)
#   2. pull the image, replace the cue-worker container (CPU and memory capped so it can't starve the app)
#   3. wait until its health check answers
# Usage: deploy-worker.sh <image-uri> <tag> <parameter-path> <region>
set -euo pipefail

IMAGE="$1"; TAG="$2"; PARAM_PATH="$3"; REGION="$4"
DIR=/opt/mywatchmate
mkdir -p "$DIR"
cd "$DIR"

echo "== loading the worker's token from $PARAM_PATH"
TOKEN=$(aws ssm get-parameter --region "$REGION" --name "$PARAM_PATH/INTERNAL_CUE_TOKEN" --with-decryption \
    --query Parameter.Value --output text)
[ -n "$TOKEN" ] || { echo "!! $PARAM_PATH/INTERNAL_CUE_TOKEN is empty"; exit 1; }
umask 077
echo "INTERNAL_CUE_TOKEN=$TOKEN" > worker.env.tmp
mv worker.env.tmp worker.env
unset TOKEN

echo "== pulling $IMAGE:$TAG"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${IMAGE%%/*}" >/dev/null
docker pull -q "$IMAGE:$TAG"

docker network inspect mywatchmate >/dev/null 2>&1 || docker network create mywatchmate >/dev/null

echo "== starting cue-worker"
docker rm -f cue-worker >/dev/null 2>&1 || true
docker run -d --name cue-worker --network mywatchmate --restart unless-stopped \
    --env-file worker.env \
    -e MWM_API_BASE=http://app:8081 \
    -e MAX_ROOMS="${MAX_ROOMS:-10}" -e DECODERS=1 -e WORKER_SHARD=0/1 \
    --cpus 1 --memory 512m \
    --log-opt max-size=20m --log-opt max-file=5 \
    "$IMAGE:$TAG" >/dev/null

echo "== waiting for the worker to become healthy"
for i in $(seq 1 40); do
    if docker exec cue-worker python -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8090/health',timeout=4).read().decode())" 2>/dev/null; then
        echo
        echo "   healthy after $((i * 3))s"
        docker image prune -f >/dev/null 2>&1 || true
        exit 0
    fi
    sleep 3
done
echo "!! the worker did not become healthy; last log lines:"
docker logs --tail 60 cue-worker
exit 1
