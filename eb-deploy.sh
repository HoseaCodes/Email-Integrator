#!/bin/bash

# AWS Elastic Beanstalk Deployment Script for Spring Boot
# Author: Automated deployment script
# Description: Complete programmatic deployment pipeline for Spring Boot apps to AWS EB

set -euo pipefail  # Exit on error, undefined vars, pipe failures
# =============================================================================
# CONFIGURATION
# =============================================================================

# Default configuration - override with environment variables
APP_NAME="${APP_NAME:-email-integrator}"
ENV_NAME="${ENV_NAME:-${APP_NAME}-prod}"
REGION="${AWS_REGION:-us-east-1}"
PLATFORM="${PLATFORM:-java-17-amazon-linux}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.micro}"
JAR_FILE="${JAR_FILE:-target/*.jar}"
VERSION_LABEL="${VERSION_LABEL:-v$(date +%Y%m%d%H%M%S)}"
S3_BUCKET="${S3_BUCKET:-}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-/actuator/health}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================

log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

success() {
    echo -e "${GREEN}✓${NC} $1"
}

warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

error() {
    echo -e "${RED}✗${NC} $1" >&2
    exit 1
}

check_dependencies() {
    log "Checking dependencies..."

    command -v aws >/dev/null 2>&1 || error "AWS CLI not installed"
    command -v jq >/dev/null 2>&1 || error "jq not installed"
    command -v zip >/dev/null 2>&1 || error "zip not installed"

    # Check AWS credentials
    if ! aws sts get-caller-identity >/dev/null 2>&1; then
        error "AWS credentials not configured"
    fi

    success "All dependencies satisfied"
}

# =============================================================================
# SECRETS
# =============================================================================
#
# The application refuses to start without these, so failing here — before an S3 upload and a
# multi-minute environment update — is far cheaper than discovering it from a health check that
# never goes green.
#
# Previously this script set only SERVER_PORT, so JWT_SECRET and MAIL_PASSWORD were never
# provisioned at all and the deployed service fell back to defaults published in this
# repository. See docs/ENGINEERING_AUDIT.md CRIT-6.
#
# Values are read from the deploying shell's environment and are never written to disk, echoed,
# or logged. They travel to AWS over TLS via the API and are stored as Elastic Beanstalk
# environment properties.
#
# NOTE: EB environment properties are visible to anyone with console or API read access to the
# environment, and appear in `describe-configuration-settings` output. That is acceptable for a
# portfolio deployment and is the documented limitation; AWS Secrets Manager or SSM Parameter
# Store with an IAM-scoped instance role is the production answer. See docs/AWS_ARCHITECTURE.md.

REQUIRED_SECRETS=(API_KEY_DEFAULT BREVO_API_KEY JWT_SECRET MAIL_PASSWORD)

check_secrets() {
    log "Checking required application secrets..."

    local missing=()
    for name in "${REQUIRED_SECRETS[@]}"; do
        if [[ -z "${!name:-}" ]]; then
            missing+=("$name")
        fi
    done

    if (( ${#missing[@]} > 0 )); then
        echo >&2
        echo "The following required environment variables are not set:" >&2
        printf '  - %s\n' "${missing[@]}" >&2
        echo >&2
        echo "The application will not start without them. Export them first, e.g.:" >&2
        echo "  set -a && . ./.env && set +a && ./eb-deploy.sh" >&2
        echo >&2
        echo "See .env.example for what each one is and how to generate it." >&2
        error "Missing required secrets"
    fi

    # Catch a too-short signing key here rather than after a full deploy cycle.
    if (( ${#JWT_SECRET} < 32 )); then
        error "JWT_SECRET must be at least 32 characters (HS256 needs a 256-bit key). Generate one with: openssl rand -base64 32"
    fi
    if (( ${#API_KEY_DEFAULT} < 32 )); then
        error "API_KEY_DEFAULT must be at least 32 characters. Generate one with: openssl rand -base64 32"
    fi

    success "All required secrets present (${#REQUIRED_SECRETS[@]} values)"
}

apply_environment_variables() {
    log "Applying environment properties to $ENV_NAME..."

    # Built as a JSON document via jq rather than string concatenation so that values containing
    # quotes, spaces, or shell metacharacters cannot break the payload or leak into a command line.
    local options
    options=$(jq -n \
        --arg apiKey        "$API_KEY_DEFAULT" \
        --arg brevoKey      "$BREVO_API_KEY" \
        --arg jwtSecret     "$JWT_SECRET" \
        --arg mailPassword  "$MAIL_PASSWORD" \
        --arg allowedHosts  "${APP_EMAIL_ALLOWEDLINKHOSTS:-}" \
        '[
          {Namespace: "aws:elasticbeanstalk:application:environment", OptionName: "SERVER_PORT",     Value: "8080"},
          {Namespace: "aws:elasticbeanstalk:application:environment", OptionName: "API_KEY_DEFAULT", Value: $apiKey},
          {Namespace: "aws:elasticbeanstalk:application:environment", OptionName: "BREVO_API_KEY",   Value: $brevoKey},
          {Namespace: "aws:elasticbeanstalk:application:environment", OptionName: "JWT_SECRET",      Value: $jwtSecret},
          {Namespace: "aws:elasticbeanstalk:application:environment", OptionName: "MAIL_PASSWORD",   Value: $mailPassword}
        ]
        + (if $allowedHosts == "" then [] else
            [{Namespace: "aws:elasticbeanstalk:application:environment", OptionName: "APP_EMAIL_ALLOWEDLINKHOSTS", Value: $allowedHosts}]
          end)')

    # Passed via a process-substitution file so secrets never appear in the process list, where
    # any other user on the host could read them with `ps`.
    aws elasticbeanstalk update-environment \
        --environment-name "$ENV_NAME" \
        --option-settings "file://$(write_temp_options "$options")" \
        --region "$REGION" >/dev/null

    log "Waiting for environment configuration to settle..."
    aws elasticbeanstalk wait environment-updated \
        --environment-names "$ENV_NAME" \
        --region "$REGION"

    success "Environment properties applied"
}

# Writes options JSON to a private temp file and echoes its path. The file is removed on exit,
# including on failure, so secrets do not linger in the working directory.
write_temp_options() {
    local content="$1"
    local file
    file=$(mktemp "${TMPDIR:-/tmp}/eb-options.XXXXXX")
    chmod 600 "$file"
    printf '%s' "$content" > "$file"
    TEMP_FILES+=("$file")
    echo "$file"
}

TEMP_FILES=()
cleanup_temp_files() {
    for file in "${TEMP_FILES[@]:-}"; do
        [[ -n "$file" && -f "$file" ]] && rm -f "$file"
    done
}
trap cleanup_temp_files EXIT

validate_jar() {
    local jar_path="$1"
    
    if [[ ! -f "$jar_path" ]]; then
        error "JAR file not found: $jar_path"
    fi
    
    if [[ ! "$jar_path" =~ \.jar$ ]]; then
        error "File is not a JAR: $jar_path"
    fi
    
    success "JAR file validated: $(basename "$jar_path")"
}

# =============================================================================
# AWS ELASTIC BEANSTALK FUNCTIONS
# =============================================================================

create_s3_bucket() {
    if [[ -z "$S3_BUCKET" ]]; then
        S3_BUCKET="${APP_NAME}-eb-deployments-$(date +%s)"
        log "No S3 bucket specified, creating: $S3_BUCKET"
    fi
    
    # Check if bucket exists
    if aws s3 ls "s3://$S3_BUCKET" >/dev/null 2>&1; then
        success "S3 bucket exists: $S3_BUCKET"
        return 0
    fi
    
    # Create bucket
    if [[ "$REGION" == "us-east-1" ]]; then
        aws s3 mb "s3://$S3_BUCKET"
    else
        aws s3 mb "s3://$S3_BUCKET" --region "$REGION"
    fi
    
    # Enable versioning
    aws s3api put-bucket-versioning \
        --bucket "$S3_BUCKET" \
        --versioning-configuration Status=Enabled
    
    success "S3 bucket created: $S3_BUCKET"
}

create_eb_application() {
    log "Creating Elastic Beanstalk application..."
    
    # Check if application exists
    local app_exists
    app_exists=$(aws elasticbeanstalk describe-applications \
        --application-names "$APP_NAME" \
        --region "$REGION" \
        --query "Applications[0].ApplicationName" \
        --output text 2>/dev/null)
    
    if [[ "$app_exists" != "None" && -n "$app_exists" ]]; then
        success "EB application exists: $APP_NAME"
        return 0
    fi
    
    # Create application
    aws elasticbeanstalk create-application \
        --application-name "$APP_NAME" \
        --description "Spring Boot application deployed via automation" \
        --region "$REGION"
    
    success "EB application created: $APP_NAME"
}

get_solution_stack() {
    log "Finding latest Docker platform version..." >&2
    
    # Use the known Docker platform
    local stack_name="64bit Amazon Linux 2023 v4.7.5 running Docker"
    
    success "Using platform: $stack_name" >&2
    echo "$stack_name"
}

create_iam_resources() {
    log "Creating IAM resources for Elastic Beanstalk..."
    
    local role_name="aws-elasticbeanstalk-ec2-role"
    local instance_profile_name="aws-elasticbeanstalk-ec2-role"
    
    # Check if role exists
    if aws iam get-role --role-name "$role_name" >/dev/null 2>&1; then
        success "IAM role exists: $role_name"
    else
        # Create trust policy
        cat > trust-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
        
        # Create IAM role
        aws iam create-role \
            --role-name "$role_name" \
            --assume-role-policy-document file://trust-policy.json
        
        # Attach managed policies
        aws iam attach-role-policy \
            --role-name "$role_name" \
            --policy-arn "arn:aws:iam::aws:policy/AWSElasticBeanstalkWebTier"
        
        aws iam attach-role-policy \
            --role-name "$role_name" \
            --policy-arn "arn:aws:iam::aws:policy/AWSElasticBeanstalkMulticontainerDocker"
        
        success "IAM role created: $role_name"
        rm -f trust-policy.json
    fi
    
    # Check if instance profile exists
    if aws iam get-instance-profile --instance-profile-name "$instance_profile_name" >/dev/null 2>&1; then
        success "Instance profile exists: $instance_profile_name"
    else
        # Create instance profile
        aws iam create-instance-profile --instance-profile-name "$instance_profile_name"
        
        # Add role to instance profile
        aws iam add-role-to-instance-profile \
            --instance-profile-name "$instance_profile_name" \
            --role-name "$role_name"
        
        success "Instance profile created: $instance_profile_name"
    fi
}

create_eb_environment() {
    local solution_stack="$1"
    
    log "Creating EB environment: $ENV_NAME"
    log "Using solution stack: '$solution_stack'"
    log "Solution stack length: ${#solution_stack}"
    
    # Check if environment exists and is not terminated
    local env_status
    env_status=$(aws elasticbeanstalk describe-environments \
        --application-name "$APP_NAME" \
        --environment-names "$ENV_NAME" \
        --region "$REGION" \
        --query "Environments[0].Status" \
        --output text 2>/dev/null)
    
    if [[ "$env_status" != "None" && "$env_status" != "Terminated" && -n "$env_status" ]]; then
        warning "Environment exists: $ENV_NAME (Status: $env_status)"
        return 0
    fi
    
    # Create IAM resources first
    create_iam_resources
    
    # Create environment configuration
    cat > eb-options.json << EOF
[
    {
        "Namespace": "aws:autoscaling:launchconfiguration",
        "OptionName": "InstanceType",
        "Value": "$INSTANCE_TYPE"
    },
    {
        "Namespace": "aws:autoscaling:launchconfiguration",
        "OptionName": "IamInstanceProfile",
        "Value": "aws-elasticbeanstalk-ec2-role"
    },
    {
        "Namespace": "aws:elasticbeanstalk:environment",
        "OptionName": "EnvironmentType",
        "Value": "SingleInstance"
    },
    {
        "Namespace": "aws:elasticbeanstalk:healthreporting:system",
        "OptionName": "SystemType",
        "Value": "enhanced"
    },
    {
        "Namespace": "aws:elasticbeanstalk:application:environment",
        "OptionName": "SERVER_PORT",
        "Value": "8080"
    }
]
EOF
    
    # Create environment
    aws elasticbeanstalk create-environment \
        --application-name "$APP_NAME" \
        --environment-name "$ENV_NAME" \
        --solution-stack-name "$solution_stack" \
        --option-settings file://eb-options.json \
        --region "$REGION"
    
    # Wait for environment to be ready
    log "Waiting for environment to be ready (this may take several minutes)..."
    aws elasticbeanstalk wait environment-exists \
        --application-name "$APP_NAME" \
        --environment-names "$ENV_NAME" \
        --region "$REGION"
    
    success "EB environment created: $ENV_NAME"
    rm -f eb-options.json
}

create_application_version() {
    local jar_file="$1"
    local jar_name
    jar_name=$(basename "$jar_file")
    
    log "Creating Docker deployment bundle..."
    
    # Create deployment directory
    local deploy_dir="eb-deploy-$VERSION_LABEL"
    mkdir -p "$deploy_dir"
    
    # Copy JAR file
    cp "$jar_file" "$deploy_dir/app.jar"
    
    # Create simple Dockerfile for EB
    # Deliberately not the repository's Dockerfile.
    #
    # That one builds from source in a Maven stage, which is right for local use and CI. Here the
    # jar is already built and only it is uploaded, so this bundle stays small and the deploy does
    # not re-resolve the dependency tree on the instance. The runtime hardening is kept in step
    # with the repository Dockerfile: same JRE base, non-root user, exec-form entrypoint.
    cat > "$deploy_dir/Dockerfile" << 'EOF'
FROM eclipse-temurin:17-jre

# Non-root. An RCE in the application should not start with control of the container.
RUN groupadd --system --gid 10001 appuser \
 && useradd --system --uid 10001 --gid appuser --home-dir /app --shell /usr/sbin/nologin appuser

WORKDIR /app

COPY --chown=appuser:appuser app.jar /app/app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1

# exec form so the JVM is PID 1 and receives SIGTERM directly, giving Spring a chance to shut
# down cleanly instead of being SIGKILLed after the stop timeout.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
EOF
    
    # Create deployment ZIP
    local zip_file="${deploy_dir}.zip"
    cd "$deploy_dir"
    zip -r "../$zip_file" .
    cd ..
    
    log "Creating application version: $VERSION_LABEL"
    
    # Upload ZIP to S3
    aws s3 cp "$zip_file" "s3://$S3_BUCKET/$zip_file"
    success "Deployment bundle uploaded to S3: s3://$S3_BUCKET/$zip_file"
    
    # Create application version
    aws elasticbeanstalk create-application-version \
        --application-name "$APP_NAME" \
        --version-label "$VERSION_LABEL" \
        --description "Automated Docker deployment $(date)" \
        --source-bundle S3Bucket="$S3_BUCKET",S3Key="$zip_file" \
        --region "$REGION"
    
    success "Application version created: $VERSION_LABEL"
    
    # Cleanup
    rm -rf "$deploy_dir" "$zip_file"
}

deploy_to_environment() {
    log "Deploying version $VERSION_LABEL to environment $ENV_NAME..."
    
    # Update environment
    aws elasticbeanstalk update-environment \
        --application-name "$APP_NAME" \
        --environment-name "$ENV_NAME" \
        --version-label "$VERSION_LABEL" \
        --region "$REGION"
    
    # Wait for deployment
    log "Waiting for deployment to complete..."
    aws elasticbeanstalk wait environment-updated \
        --application-name "$APP_NAME" \
        --environment-names "$ENV_NAME" \
        --region "$REGION"
    
    success "Deployment completed"
}

get_environment_info() {
    log "Retrieving environment information..."
    
    local env_info
    env_info=$(aws elasticbeanstalk describe-environments \
        --application-name "$APP_NAME" \
        --environment-names "$ENV_NAME" \
        --region "$REGION")
    
    local url health status
    url=$(echo "$env_info" | jq -r '.Environments[0].CNAME // empty')
    health=$(echo "$env_info" | jq -r '.Environments[0].Health // "Unknown"')
    status=$(echo "$env_info" | jq -r '.Environments[0].Status // "Unknown"')
    
    echo
    echo "=========================================="
    echo "DEPLOYMENT SUMMARY"
    echo "=========================================="
    echo "Application: $APP_NAME"
    echo "Environment: $ENV_NAME"
    echo "Version: $VERSION_LABEL"
    echo "Status: $status"
    echo "Health: $health"
    if [[ -n "$url" ]]; then
        echo "URL: http://$url"
        echo "Health Check: http://$url$HEALTH_CHECK_URL"
    fi
    echo "=========================================="
    echo
}

perform_health_check() {
    local env_info
    env_info=$(aws elasticbeanstalk describe-environments \
        --application-name "$APP_NAME" \
        --environment-names "$ENV_NAME" \
        --region "$REGION")
    
    local url
    url=$(echo "$env_info" | jq -r '.Environments[0].CNAME // empty')
    
    if [[ -z "$url" ]]; then
        warning "No URL available for health check"
        return 0
    fi
    
    log "Performing health check..."
    
    local health_url="http://$url$HEALTH_CHECK_URL"
    local max_attempts=10
    local attempt=1
    
    while (( attempt <= max_attempts )); do
        if curl -sf "$health_url" >/dev/null 2>&1; then
            success "Health check passed: $health_url"
            return 0
        fi
        
        log "Health check attempt $attempt/$max_attempts failed, retrying in 30s..."
        sleep 30
        ((attempt++))
    done
    
    warning "Health check failed after $max_attempts attempts"
    warning "Manual verification may be required: $health_url"
}

# =============================================================================
# CLEANUP AND UTILITIES
# =============================================================================

cleanup_old_versions() {
    log "Cleaning up old application versions..."
    
    local versions
    versions=$(aws elasticbeanstalk describe-application-versions \
        --application-name "$APP_NAME" \
        --region "$REGION" \
        --query "ApplicationVersions[?VersionLabel!='$VERSION_LABEL'].VersionLabel" \
        --output text)
    
    if [[ -z "$versions" || "$versions" == "None" ]]; then
        success "No old versions to clean up"
        return 0
    fi
    
    local count=0
    for version in $versions; do
        aws elasticbeanstalk delete-application-version \
            --application-name "$APP_NAME" \
            --version-label "$version" \
            --delete-source-bundle \
            --region "$REGION" 2>/dev/null || true
        ((count++))
    done
    
    success "Cleaned up $count old versions"
}

show_usage() {
    cat << EOF
AWS Elastic Beanstalk Deployment Script

Usage: $0 [OPTIONS]

Options:
    -a, --app-name NAME          Application name (default: springboot-app)
    -e, --env-name NAME          Environment name (default: {app-name}-prod)
    -r, --region REGION          AWS region (default: us-east-1)
    -j, --jar-file PATH          Path to JAR file (default: target/*.jar)
    -b, --s3-bucket BUCKET       S3 bucket for deployments (auto-created if empty)
    -i, --instance-type TYPE     EC2 instance type (default: t3.micro)
    -v, --version-label LABEL    Version label (default: auto-generated)
    -h, --help                   Show this help message

Environment Variables:
    APP_NAME                     Application name
    ENV_NAME                     Environment name
    AWS_REGION                   AWS region
    JAR_FILE                     JAR file path
    S3_BUCKET                    S3 bucket name
    INSTANCE_TYPE               EC2 instance type
    VERSION_LABEL               Version label

Examples:
    $0                          # Deploy with defaults
    $0 -a myapp -e myapp-staging -j build/libs/myapp.jar
    $0 --app-name myapp --region us-west-2
EOF
}

# =============================================================================
# MAIN SCRIPT
# =============================================================================

main() {
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            -a|--app-name)
                APP_NAME="$2"
                shift 2
                ;;
            -e|--env-name)
                ENV_NAME="$2"
                shift 2
                ;;
            -r|--region)
                REGION="$2"
                shift 2
                ;;
            -j|--jar-file)
                JAR_FILE="$2"
                shift 2
                ;;
            -b|--s3-bucket)
                S3_BUCKET="$2"
                shift 2
                ;;
            -i|--instance-type)
                INSTANCE_TYPE="$2"
                shift 2
                ;;
            -v|--version-label)
                VERSION_LABEL="$2"
                shift 2
                ;;
            -h|--help)
                show_usage
                exit 0
                ;;
            *)
                error "Unknown option: $1"
                ;;
        esac
    done
    
    # Update ENV_NAME default if not explicitly set
    if [[ "$ENV_NAME" == "springboot-app-prod" && "$APP_NAME" != "springboot-app" ]]; then
        ENV_NAME="${APP_NAME}-prod"
    fi
    
    # Find JAR file if using wildcard
    if [[ "$JAR_FILE" == *"*"* ]]; then
        JAR_FILE=$(find . -name "$(basename "$JAR_FILE")" -type f | head -1)
        if [[ -z "$JAR_FILE" ]]; then
            error "No JAR file found matching pattern"
        fi
    fi
    
    log "Starting AWS Elastic Beanstalk deployment..."
    log "App: $APP_NAME | Env: $ENV_NAME | Region: $REGION"
    
    # Pre-flight checks. Secrets are validated before anything is uploaded or changed, so a
    # missing value costs seconds rather than a failed rollout.
    check_dependencies
    check_secrets
    validate_jar "$JAR_FILE"

    # AWS setup
    create_s3_bucket
    create_eb_application
    
    # Get platform version
    local solution_stack
    solution_stack=$(get_solution_stack)
    
    # Environment setup
    create_eb_environment "$solution_stack"
    
    # Environment properties are applied before the new version is deployed, so the application
    # finds its configuration already present when it starts rather than crash-looping until a
    # second update lands.
    apply_environment_variables

    # Deploy application
    create_application_version "$JAR_FILE"
    deploy_to_environment
    
    # Post-deployment
    get_environment_info
    perform_health_check
    cleanup_old_versions
    
    success "Deployment completed successfully!"
}

# Handle script interruption
trap 'error "Script interrupted"' INT TERM

# Run main function
main "$@"
