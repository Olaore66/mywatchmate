#!/usr/bin/env bash
# Creates the staging environment on AWS, or completes it if a previous run stopped part-way.
# Safe to re-run: anything that already exists is reused, never recreated.
#
#   Image registry (ECR) · firewall rules · secrets in Parameter Store · PostgreSQL (RDS)
#   · server role · fixed public IP · server (EC2, Amazon Linux + Docker)
#
# Costs money (about $35-40/month in eu-west-2). Remove everything with teardown-staging.sh.
# Usage (Git Bash):  deploy/setup-staging.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1    # Git Bash: keep /mywatchmate/staging as-is, not a Windows path
export AWS_PAGER=""
command -v aws >/dev/null || export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
cd "$(dirname "$0")"
source ./staging.conf
export AWS_DEFAULT_REGION="$REGION"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
step() { echo; echo "== $*"; }
param_exists() { aws ssm get-parameter --name "$PARAM_PATH/$1" >/dev/null 2>&1; }
put_param() { aws ssm put-parameter --name "$PARAM_PATH/$1" --type "$2" --value "$3" --overwrite >/dev/null; }
none() { [ -z "$1" ] || [ "$1" = "None" ]; }
echo "Account $ACCOUNT, region $REGION, prefix $NAME"

step "1/7 Image registry (ECR): $ECR_REPO"
if aws ecr describe-repositories --repository-names "$ECR_REPO" >/dev/null 2>&1; then
    echo "   exists"
else
    aws ecr create-repository --repository-name "$ECR_REPO" --image-scanning-configuration scanOnPush=true \
        --tags Key=project,Value=mywatchmate >/dev/null
    aws ecr put-lifecycle-policy --repository-name "$ECR_REPO" --lifecycle-policy-text \
        '{"rules":[{"rulePriority":1,"description":"Keep the last 10 images","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]}' >/dev/null
    echo "   created (keeps the last 10 images)"
fi

step "2/7 Firewall rules"
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
sg_id() { aws ec2 describe-security-groups --filters "Name=group-name,Values=$1" "Name=vpc-id,Values=$VPC" \
    --query 'SecurityGroups[0].GroupId' --output text; }
APP_SG=$(sg_id "$NAME-app")
if none "$APP_SG"; then
    APP_SG=$(aws ec2 create-security-group --group-name "$NAME-app" --vpc-id "$VPC" \
        --description "MyWatchMate staging server: HTTP and HTTPS only" --query GroupId --output text)
    aws ec2 authorize-security-group-ingress --group-id "$APP_SG" --ip-permissions \
        'IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0}],Ipv6Ranges=[{CidrIpv6=::/0}]' \
        'IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}],Ipv6Ranges=[{CidrIpv6=::/0}]' >/dev/null
    echo "   server: created $APP_SG (ports 80, 443)"
else
    echo "   server: exists $APP_SG"
fi
DB_SG=$(sg_id "$NAME-db")
if none "$DB_SG"; then
    DB_SG=$(aws ec2 create-security-group --group-name "$NAME-db" --vpc-id "$VPC" \
        --description "MyWatchMate staging database: reachable only from the staging server" --query GroupId --output text)
    aws ec2 authorize-security-group-ingress --group-id "$DB_SG" --protocol tcp --port 5432 --source-group "$APP_SG" >/dev/null
    echo "   database: created $DB_SG (port 5432 from the server only)"
else
    echo "   database: exists $DB_SG"
fi

step "3/7 Secrets and settings (Parameter Store: $PARAM_PATH)"
if param_exists JWT_SECRET; then echo "   JWT_SECRET exists"; else
    put_param JWT_SECRET SecureString "$(openssl rand -base64 48 | tr -d '\n')"; echo "   JWT_SECRET generated"; fi
if param_exists DB_PASSWORD; then echo "   DB_PASSWORD exists"; else
    put_param DB_PASSWORD SecureString "$(openssl rand -hex 24)"; echo "   DB_PASSWORD generated"; fi
put_param DB_USERNAME String "$DB_USER"
put_param ALLOWED_ORIGINS String "$ALLOWED_ORIGINS"
put_param FRONTEND_URL String "$FRONTEND_URL"
echo "   DB_USERNAME, ALLOWED_ORIGINS, FRONTEND_URL set"

step "4/7 Database (RDS PostgreSQL $DB_ENGINE_MAJOR, $DB_CLASS)"
if aws rds describe-db-instances --db-instance-identifier "$DB_INSTANCE" >/dev/null 2>&1; then
    echo "   exists"
else
    VERSION=$(aws rds describe-db-engine-versions --engine postgres \
        --query "DBEngineVersions[?starts_with(EngineVersion, '$DB_ENGINE_MAJOR.')].EngineVersion | [-1]" --output text)
    DB_PASSWORD=$(aws ssm get-parameter --name "$PARAM_PATH/DB_PASSWORD" --with-decryption --query Parameter.Value --output text)
    aws rds create-db-instance --db-instance-identifier "$DB_INSTANCE" \
        --engine postgres --engine-version "$VERSION" --db-instance-class "$DB_CLASS" \
        --allocated-storage 20 --storage-type gp3 --storage-encrypted \
        --db-name "$DB_NAME" --master-username "$DB_USER" --master-user-password "$DB_PASSWORD" \
        --vpc-security-group-ids "$DB_SG" --no-publicly-accessible --no-multi-az \
        --backup-retention-period 1 --copy-tags-to-snapshot \
        --tags "Key=Name,Value=$NAME" Key=project,Value=mywatchmate >/dev/null
    unset DB_PASSWORD
    echo "   creating PostgreSQL $VERSION; this takes about 10 minutes"
fi
aws rds wait db-instance-available --db-instance-identifier "$DB_INSTANCE"
DB_HOST=$(aws rds describe-db-instances --db-instance-identifier "$DB_INSTANCE" --query 'DBInstances[0].Endpoint.Address' --output text)
put_param DB_URL String "jdbc:postgresql://$DB_HOST:5432/$DB_NAME?sslmode=require"
echo "   available at $DB_HOST (private: only the server can reach it)"

step "5/7 Server role (read its own settings, pull images, remote access without SSH)"
ROLE="$NAME-server"
if aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
    echo "   exists"
else
    aws iam create-role --role-name "$ROLE" --tags Key=project,Value=mywatchmate --assume-role-policy-document \
        '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
    aws iam attach-role-policy --role-name "$ROLE" --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
    aws iam attach-role-policy --role-name "$ROLE" --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
    aws iam put-role-policy --role-name "$ROLE" --policy-name read-staging-settings --policy-document \
        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"ssm:GetParametersByPath\",\"ssm:GetParameter\",\"ssm:GetParameters\"],\"Resource\":[\"arn:aws:ssm:$REGION:$ACCOUNT:parameter$PARAM_PATH\",\"arn:aws:ssm:$REGION:$ACCOUNT:parameter$PARAM_PATH/*\"]}]}"
    aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
    aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"
    echo "   created; waiting for IAM to propagate"
    sleep 15
fi

step "6/7 Fixed public IP"
ALLOC=$(aws ec2 describe-addresses --filters "Name=tag:Name,Values=$NAME" --query 'Addresses[0].AllocationId' --output text)
if none "$ALLOC"; then
    ALLOC=$(aws ec2 allocate-address --domain vpc --query AllocationId --output text \
        --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$NAME},{Key=project,Value=mywatchmate}]")
    echo "   allocated"
fi
IP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC" --query 'Addresses[0].PublicIp' --output text)
SITE="${IP//./-}.sslip.io"
put_param SITE_ADDRESS String "$SITE"
echo "   $IP → https://$SITE"

step "7/7 Server (EC2 $INSTANCE_TYPE, Amazon Linux 2023)"
INSTANCE=$(aws ec2 describe-instances --query 'Reservations[0].Instances[0].InstanceId' --output text \
    --filters "Name=tag:Name,Values=$NAME" Name=instance-state-name,Values=pending,running,stopping,stopped)
if none "$INSTANCE"; then
    USER_DATA='#!/bin/bash
set -eux
dnf install -y docker
systemctl enable --now docker
mkdir -p /opt/mywatchmate'
    INSTANCE=$(aws ec2 run-instances \
        --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
        --instance-type "$INSTANCE_TYPE" --iam-instance-profile "Name=$ROLE" --security-group-ids "$APP_SG" \
        --metadata-options HttpTokens=required,HttpEndpoint=enabled \
        --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3,Encrypted=true}' \
        --user-data "$USER_DATA" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NAME},{Key=project,Value=mywatchmate}]" \
            "ResourceType=volume,Tags=[{Key=Name,Value=$NAME},{Key=project,Value=mywatchmate}]" \
        --query 'Instances[0].InstanceId' --output text)
    echo "   launched $INSTANCE"
else
    echo "   exists $INSTANCE"
fi
aws ec2 wait instance-running --instance-ids "$INSTANCE"
aws ec2 associate-address --instance-id "$INSTANCE" --allocation-id "$ALLOC" >/dev/null
echo "   running, IP attached; waiting for remote access (Session Manager)"
for i in $(seq 1 60); do
    STATUS=$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$INSTANCE" \
        --query 'InstanceInformationList[0].PingStatus' --output text)
    [ "$STATUS" = "Online" ] && break
    sleep 10
done
[ "$STATUS" = "Online" ] || { echo "!! server didn't come online for remote access"; exit 1; }
echo "   online"

echo
echo "Staging is ready for a release:"
echo "   URL       https://$SITE"
echo "   server    $INSTANCE"
echo "   database  $DB_HOST"
echo "Next: deploy/release.sh"
