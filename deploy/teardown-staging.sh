#!/usr/bin/env bash
# Deletes the whole staging environment, so it stops costing money.
# The database gets a final snapshot first (a few cents a month), unless --no-snapshot is given.
# Usage (Git Bash):  deploy/teardown-staging.sh [--no-snapshot]
set -euo pipefail
export MSYS_NO_PATHCONV=1
export AWS_PAGER=""
command -v aws >/dev/null || export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
cd "$(dirname "$0")"
source ./staging.conf
export AWS_DEFAULT_REGION="$REGION"
SNAPSHOT=yes
[ "${1:-}" = "--no-snapshot" ] && SNAPSHOT=no

echo "This permanently deletes the staging server, database, image registry and settings in $REGION."
[ "$SNAPSHOT" = yes ] && echo "A final database snapshot is kept." || echo "NO database snapshot will be kept."
read -r -p "Type 'delete staging' to continue: " ANSWER
[ "$ANSWER" = "delete staging" ] || { echo "Cancelled."; exit 1; }
none() { [ -z "$1" ] || [ "$1" = "None" ]; }

echo "== server"
INSTANCE=$(aws ec2 describe-instances --query 'Reservations[0].Instances[0].InstanceId' --output text \
    --filters "Name=tag:Name,Values=$NAME" Name=instance-state-name,Values=pending,running,stopping,stopped)
if ! none "$INSTANCE"; then
    aws ec2 terminate-instances --instance-ids "$INSTANCE" >/dev/null
    aws ec2 wait instance-terminated --instance-ids "$INSTANCE"
    echo "   terminated $INSTANCE"
fi

echo "== public IP"
ALLOC=$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=$NAME" --query 'Addresses[0].AllocationId' --output text)
none "$ALLOC" || { aws ec2 release-address --allocation-id "$ALLOC"; echo "   released"; }

echo "== database"
if aws rds describe-db-instances --db-instance-identifier "$DB_INSTANCE" >/dev/null 2>&1; then
    if [ "$SNAPSHOT" = yes ]; then
        SNAP="$DB_INSTANCE-final-$(date +%Y%m%d-%H%M)"
        aws rds delete-db-instance --db-instance-identifier "$DB_INSTANCE" --final-db-snapshot-identifier "$SNAP" >/dev/null
        echo "   deleting (final snapshot: $SNAP)"
    else
        aws rds delete-db-instance --db-instance-identifier "$DB_INSTANCE" --skip-final-snapshot >/dev/null
        echo "   deleting (no snapshot)"
    fi
    aws rds wait db-instance-deleted --db-instance-identifier "$DB_INSTANCE"
    echo "   deleted"
fi

echo "== firewall rules"
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
for group in "$NAME-db" "$NAME-app"; do
    SG=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$group" "Name=vpc-id,Values=$VPC" \
        --query 'SecurityGroups[0].GroupId' --output text)
    none "$SG" || { aws ec2 delete-security-group --group-id "$SG"; echo "   deleted $group"; }
done

echo "== server role"
ROLE="$NAME-server"
if aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
    aws iam remove-role-from-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE" 2>/dev/null || true
    aws iam delete-instance-profile --instance-profile-name "$ROLE" 2>/dev/null || true
    for arn in $(aws iam list-attached-role-policies --role-name "$ROLE" --query 'AttachedPolicies[].PolicyArn' --output text); do
        aws iam detach-role-policy --role-name "$ROLE" --policy-arn "$arn"
    done
    for policy in $(aws iam list-role-policies --role-name "$ROLE" --query 'PolicyNames' --output text); do
        aws iam delete-role-policy --role-name "$ROLE" --policy-name "$policy"
    done
    aws iam delete-role --role-name "$ROLE"
    echo "   deleted"
fi

echo "== settings and secrets"
for name in $(aws ssm get-parameters-by-path --path "$PARAM_PATH" --query 'Parameters[].Name' --output text); do
    aws ssm delete-parameter --name "$name"
done
echo "   deleted"

echo "== image registry"
aws ecr delete-repository --repository-name "$ECR_REPO" --force >/dev/null 2>&1 && echo "   deleted" || echo "   none"

echo
echo "Staging removed."
