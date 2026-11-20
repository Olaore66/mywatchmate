#!/usr/bin/env bash
# Ships the AI cue worker to staging as a second container next to the app.
# The image goes into the existing ECR repository (tags worker-<commit> and worker-latest), so no new AWS resources.
# Run the worker tests first (cue-worker: .venv/Scripts/python -m pytest). Needs Docker Desktop running.
# Usage (Git Bash):  deploy/release-worker.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
export AWS_PAGER=""
command -v aws >/dev/null || export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
DEPLOY_DIR="$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })"
ROOT="$(dirname "$DEPLOY_DIR")"
source "$DEPLOY_DIR/staging.conf"
export AWS_DEFAULT_REGION="$REGION"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
IMAGE="$REGISTRY/$ECR_REPO"
TAG="worker-$(git -C "$ROOT" rev-parse --short HEAD)"
git -C "$ROOT" diff --quiet HEAD -- cue-worker || TAG="$TAG-dirty"

INSTANCE=$(aws ec2 describe-instances --query 'Reservations[0].Instances[0].InstanceId' --output text \
    --filters "Name=tag:Name,Values=$NAME" Name=instance-state-name,Values=running)
[ "$INSTANCE" != "None" ] || { echo "!! no running staging server; run deploy/setup-staging.sh"; exit 1; }

echo "== building image $ECR_REPO:$TAG"
docker build -q -t "$IMAGE:$TAG" -t "$IMAGE:worker-latest" "$ROOT/cue-worker" >/dev/null

retry() {
    for attempt in 1 2 3 4 5; do
        "$@" && return 0
        echo "   attempt $attempt failed, retrying in 15s"
        sleep 15
    done
    return 1
}

echo "== pushing to $REGISTRY"
ecr_login() { aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null; }
retry ecr_login
retry docker push -q "$IMAGE:$TAG"
retry docker push -q "$IMAGE:worker-latest"

echo "== starting $TAG on $INSTANCE"
DEPLOY_B64=$(base64 -w0 "$DEPLOY_DIR/server/deploy-worker.sh")
COMMANDS=$(cat <<EOF
{"commands":[
 "mkdir -p /opt/mywatchmate",
 "echo $DEPLOY_B64 | base64 -d > /opt/mywatchmate/deploy-worker.sh && chmod 700 /opt/mywatchmate/deploy-worker.sh",
 "/opt/mywatchmate/deploy-worker.sh $IMAGE $TAG $PARAM_PATH $REGION"
]}
EOF
)
COMMAND_ID=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
    --comment "release $TAG" --timeout-seconds 600 --parameters "$COMMANDS" --query Command.CommandId --output text)
STATUS=Pending
for i in $(seq 1 120); do
    sleep 5
    STATUS=$(aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE" \
        --query Status --output text 2>/dev/null || echo Pending)
    case "$STATUS" in Pending|InProgress|Delayed) continue ;; *) break ;; esac
done
aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text | sed 's/^/   /'
[ "$STATUS" = "Success" ] || { echo "!! worker release failed on the server ($STATUS)"; exit 1; }
echo "Released $TAG"
