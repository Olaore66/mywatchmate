#!/usr/bin/env bash
# Gives a registered staging user a staff role (MODERATOR or ADMIN), or takes it away (none). Staff can
# review reported payment links. There is deliberately no API for this: only someone with AWS access can.
# The change shows on their /me at once; they don't need to sign in again.
# Usage (Git Bash):  deploy/grant-staff.sh someone@example.com MODERATOR|ADMIN|none
set -euo pipefail
export MSYS_NO_PATHCONV=1
export AWS_PAGER=""
command -v aws >/dev/null || export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$DEPLOY_DIR/staging.conf"
export AWS_DEFAULT_REGION="$REGION"

EMAIL="${1:-}"
ROLE="${2:-}"
[[ "$EMAIL" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || { echo "usage: $0 email MODERATOR|ADMIN|none"; exit 1; }
case "$ROLE" in
    MODERATOR|ADMIN) VALUE="'$ROLE'" ;;
    none) VALUE="NULL" ;;
    *) echo "usage: $0 email MODERATOR|ADMIN|none"; exit 1 ;;
esac

INSTANCE=$(aws ec2 describe-instances --query 'Reservations[0].Instances[0].InstanceId' --output text \
    --filters "Name=tag:Name,Values=$NAME" Name=instance-state-name,Values=running)
[ "$INSTANCE" != "None" ] || { echo "!! no running staging server"; exit 1; }

# Runs on the server: the database password comes from Parameter Store there and is never printed.
# The email and role were checked above; the email still goes to psql as a variable, not pasted into SQL.
REMOTE=$(cat <<EOF
set -e
get() { aws ssm get-parameter --region $REGION --name "$PARAM_PATH/\$1" \$2 --query Parameter.Value --output text; }
URL=\$(get DB_URL); HOSTPORT=\${URL#jdbc:postgresql://}; HOSTPORT=\${HOSTPORT%%/*}; DBHOST=\${HOSTPORT%%:*}
export PGPASSWORD=\$(get DB_PASSWORD --with-decryption) PGUSER=\$(get DB_USERNAME)
docker run --rm -i -e PGPASSWORD -e PGUSER postgres:17-alpine \
  psql "host=\$DBHOST dbname=$DB_NAME sslmode=require" -tA -v ON_ERROR_STOP=1 -v email="$EMAIL" <<'SQL'
UPDATE users SET staff_role = $VALUE WHERE lower(email) = lower(:'email') RETURNING 'updated: ' || coalesce(staff_role, 'no staff role');
SQL
EOF
)
B64=$(printf '%s' "$REMOTE" | base64 -w0)
COMMAND_ID=$(aws ssm send-command --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
    --comment "grant staff role" --parameters "{\"commands\":[\"echo $B64 | base64 -d | bash\"]}" \
    --query Command.CommandId --output text)
for i in $(seq 1 60); do
    sleep 3
    STATUS=$(aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE" \
        --query Status --output text 2>/dev/null || echo Pending)
    case "$STATUS" in Pending|InProgress|Delayed) continue ;; *) break ;; esac
done
OUT=$(aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text)
echo "$OUT" | sed 's/^/   /'
[ "$STATUS" = "Success" ] || { echo "!! failed ($STATUS)"; exit 1; }
echo "$OUT" | grep -q "updated:" || { echo "!! no account with that email; they must sign up first"; exit 1; }
