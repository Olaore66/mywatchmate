#!/usr/bin/env bash
# Ships the current code to staging: build the image, push it to ECR, start it on the server.
# Run the tests first (.\mvnw.cmd test). Needs Docker Desktop running and setup-staging.sh done.
# Usage (Git Bash):  deploy/release.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
export AWS_PAGER=""
command -v aws >/dev/null || export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DEPLOY_DIR")"
source "$DEPLOY_DIR/staging.conf"
export AWS_DEFAULT_REGION="$REGION"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
IMAGE="$REGISTRY/$ECR_REPO"
TAG=$(git -C "$ROOT" rev-parse --short HEAD)
git -C "$ROOT" diff --quiet HEAD || TAG="$TAG-dirty"

INSTANCE=$(aws ec2 describe-instances --query 'Reservations[0].Instances[0].InstanceId' --output text \
    --filters "Name=tag:Name,Values=$NAME" Name=instance-state-name,Values=running)
[ "$INSTANCE" != "None" ] || { echo "!! no running staging server; run deploy/setup-staging.sh"; exit 1; }
SITE=$(aws ssm get-parameter --name "$PARAM_PATH/SITE_ADDRESS" --query Parameter.Value --output text)

echo "== building the jar"
# Java 21 is required; this machine's default JAVA_HOME may point elsewhere
if ! "${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | grep -q '"21'; then
    JDK21=$(ls -d "/c/Program Files/Java/jdk-21"* 2>/dev/null | head -1)
    [ -n "$JDK21" ] || { echo "!! Java 21 not found; set JAVA_HOME to a JDK 21"; exit 1; }
    export JAVA_HOME="$(cygpath -w "$JDK21")"    # mvnw.cmd needs a Windows path
fi
(cd "$ROOT" && ./mvnw.cmd -B -q package -DskipTests)

echo "== building image $ECR_REPO:$TAG"
docker build -q -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$ROOT" >/dev/null

echo "== pushing to $REGISTRY"
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
docker push -q "$IMAGE:$TAG"
docker push -q "$IMAGE:latest"

echo "== starting $TAG on $INSTANCE"
DEPLOY_B64=$(base64 -w0 "$DEPLOY_DIR/server/deploy.sh")
CADDY_B64=$(base64 -w0 "$DEPLOY_DIR/server/Caddyfile")
COMMANDS=$(cat <<EOF
{"commands":[
 "mkdir -p /opt/mywatchmate",
 "echo $DEPLOY_B64 | base64 -d > /opt/mywatchmate/deploy.sh && chmod 700 /opt/mywatchmate/deploy.sh",
 "echo $CADDY_B64 | base64 -d > /opt/mywatchmate/Caddyfile",
 "for i in \$(seq 1 60); do systemctl is-active --quiet docker && break; sleep 5; done",
 "/opt/mywatchmate/deploy.sh $IMAGE $TAG $SITE $PARAM_PATH $REGION"
]}
EOF
)
COMMAND_ID=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
    --comment "release $TAG" --timeout-seconds 900 --parameters "$COMMANDS" --query Command.CommandId --output text)
STATUS=Pending
for i in $(seq 1 180); do
    sleep 5
    STATUS=$(aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE" \
        --query Status --output text 2>/dev/null || echo Pending)
    case "$STATUS" in Pending|InProgress|Delayed) continue ;; *) break ;; esac
done
aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text | sed 's/^/   /'
[ "$STATUS" = "Success" ] || { echo "!! release failed on the server ($STATUS)"; exit 1; }

echo "== checking https://$SITE (the first release also gets its HTTPS certificate)"
for i in $(seq 1 30); do
    if curl -fsS --max-time 10 "https://$SITE/actuator/health" 2>/dev/null | grep -q '"UP"'; then
        echo
        echo "Released $TAG"
        echo "   API      https://$SITE"
        echo "   Swagger  https://$SITE/swagger-ui.html"
        echo "   Health   https://$SITE/actuator/health"
        exit 0
    fi
    sleep 5
done
echo "!! the app is running on the server but https://$SITE isn't answering yet; try again in a minute"
exit 1
