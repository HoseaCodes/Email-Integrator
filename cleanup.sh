#!/bin/bash

# Email Integrator Cleanup Script
# Cleans up AWS resources, local build artifacts, and temporary files

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
APP_NAME="emailintegrator"
ENV_NAME="emailintegrator-prod"
REGION="us-east-1"
FORCE=false
DRY_RUN=false
CLEANUP_AWS=false
CLEANUP_LOCAL=false
CLEANUP_ALL=false

# Logging functions
log() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# Show usage
show_usage() {
    cat << EOF
Email Integrator Cleanup Script

Usage: $0 [OPTIONS]

OPTIONS:
    --aws                   Clean up AWS resources only
    --local                 Clean up local files only
    --all                   Clean up both AWS and local resources
    --app-name NAME         Application name (default: emailintegrator)
    --env-name NAME         Environment name (default: emailintegrator-prod)
    --region REGION         AWS region (default: us-east-1)
    --force                 Skip confirmation prompts
    --dry-run               Show what would be cleaned without executing
    -h, --help              Show this help message

EXAMPLES:
    $0 --local              # Clean only local build artifacts
    $0 --aws --force        # Clean AWS resources without prompts
    $0 --all --dry-run      # Show what would be cleaned
    $0 --aws --app-name myapp --region us-west-2

CLEANUP CATEGORIES:
    AWS Resources:
        - Elastic Beanstalk application and environment
        - Application versions
        - S3 deployment artifacts
        
    Local Files:
        - Maven target/ directory
        - Build artifacts (*.jar)
        - Log files (*.log)
        - Temporary files
        - IDE files (.DS_Store, etc.)
EOF
}

# Check if AWS CLI is available
check_aws_cli() {
    if ! command -v aws &> /dev/null; then
        error "AWS CLI is not installed or not in PATH"
        exit 1
    fi
}

# Confirm action with user
confirm_action() {
    local message="$1"
    if [[ "$FORCE" == "true" ]]; then
        return 0
    fi
    
    echo -e "${YELLOW}$message${NC}"
    read -p "Are you sure? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log "Operation cancelled by user"
        exit 0
    fi
}

# Clean up AWS Elastic Beanstalk resources
cleanup_aws_resources() {
    log "Starting AWS resource cleanup..."
    
    if [[ "$DRY_RUN" == "true" ]]; then
        log "[DRY RUN] Would clean up AWS resources:"
        log "  - Elastic Beanstalk environment: $ENV_NAME"
        log "  - Elastic Beanstalk application: $APP_NAME"
        log "  - Application versions for $APP_NAME"
        return 0
    fi
    
    check_aws_cli
    
    # Check if environment exists
    if aws elasticbeanstalk describe-environments --environment-names "$ENV_NAME" --region "$REGION" --query 'Environments[0].EnvironmentName' --output text 2>/dev/null | grep -q "$ENV_NAME"; then
        log "Terminating Elastic Beanstalk environment: $ENV_NAME"
        aws elasticbeanstalk terminate-environment \
            --environment-name "$ENV_NAME" \
            --region "$REGION" || warn "Failed to terminate environment $ENV_NAME"
        
        # Wait for environment termination
        log "Waiting for environment termination (this may take several minutes)..."
        aws elasticbeanstalk wait environment-terminated \
            --environment-names "$ENV_NAME" \
            --region "$REGION" || warn "Environment termination wait timed out"
    else
        log "Environment $ENV_NAME not found or already terminated"
    fi
    
    # Check if application exists
    if aws elasticbeanstalk describe-applications --application-names "$APP_NAME" --region "$REGION" --query 'Applications[0].ApplicationName' --output text 2>/dev/null | grep -q "$APP_NAME"; then
        log "Deleting Elastic Beanstalk application: $APP_NAME"
        aws elasticbeanstalk delete-application \
            --application-name "$APP_NAME" \
            --terminate-env-by-force \
            --region "$REGION" || warn "Failed to delete application $APP_NAME"
    else
        log "Application $APP_NAME not found or already deleted"
    fi
    
    success "AWS resource cleanup completed"
}

# Clean up local files and directories
cleanup_local_files() {
    log "Starting local file cleanup..."
    
    local files_to_clean=(
        "target/"
        "*.jar"
        "*.log"
        ".DS_Store"
        "*.tmp"
        "*.temp"
        ".mvn/wrapper/maven-wrapper.jar"
        "bin/"
        ".settings/"
        ".project"
        ".classpath"
        "*.iml"
        ".idea/"
        "*.swp"
        "*.swo"
        "*~"
    )
    
    if [[ "$DRY_RUN" == "true" ]]; then
        log "[DRY RUN] Would clean up local files:"
        for pattern in "${files_to_clean[@]}"; do
            ls $pattern 2>/dev/null | head -5 | while read -r file; do
                log "  - $file"
            done
        done
        return 0
    fi
    
    local cleaned_count=0
    
    for pattern in "${files_to_clean[@]}"; do
        if ls $pattern 2>/dev/null >/dev/null; then
            log "Removing: $pattern"
            rm -rf $pattern 2>/dev/null || warn "Failed to remove $pattern"
            ((cleaned_count++))
        fi
    done
    
    # Clean Maven cache (optional)
    if [[ -d "$HOME/.m2/repository" ]]; then
        log "Cleaning Maven repository cache..."
        find "$HOME/.m2/repository" -name "*.lastUpdated" -delete 2>/dev/null || true
    fi
    
    success "Local file cleanup completed ($cleaned_count items cleaned)"
}

# Show cleanup summary
show_cleanup_summary() {
    log "Cleanup Summary:"
    if [[ "$CLEANUP_AWS" == "true" || "$CLEANUP_ALL" == "true" ]]; then
        log "  ✓ AWS resources cleaned"
    fi
    if [[ "$CLEANUP_LOCAL" == "true" || "$CLEANUP_ALL" == "true" ]]; then
        log "  ✓ Local files cleaned"
    fi
    success "Cleanup completed successfully!"
}

# Main execution
main() {
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --aws)
                CLEANUP_AWS=true
                shift
                ;;
            --local)
                CLEANUP_LOCAL=true
                shift
                ;;
            --all)
                CLEANUP_ALL=true
                shift
                ;;
            --app-name)
                APP_NAME="$2"
                shift 2
                ;;
            --env-name)
                ENV_NAME="$2"
                shift 2
                ;;
            --region)
                REGION="$2"
                shift 2
                ;;
            --force)
                FORCE=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            -h|--help)
                show_usage
                exit 0
                ;;
            *)
                error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done
    
    # Validate arguments
    if [[ "$CLEANUP_AWS" == "false" && "$CLEANUP_LOCAL" == "false" && "$CLEANUP_ALL" == "false" ]]; then
        error "Please specify what to clean: --aws, --local, or --all"
        show_usage
        exit 1
    fi
    
    # Show what will be cleaned
    log "Email Integrator Cleanup Script"
    log "==============================="
    if [[ "$DRY_RUN" == "true" ]]; then
        warn "DRY RUN MODE - No actual changes will be made"
    fi
    
    # Confirm cleanup
    if [[ "$CLEANUP_ALL" == "true" ]]; then
        confirm_action "This will clean up ALL AWS resources and local files for $APP_NAME"
    elif [[ "$CLEANUP_AWS" == "true" ]]; then
        confirm_action "This will clean up AWS resources for $APP_NAME in region $REGION"
    elif [[ "$CLEANUP_LOCAL" == "true" ]]; then
        confirm_action "This will clean up local build artifacts and temporary files"
    fi
    
    # Execute cleanup
    if [[ "$CLEANUP_AWS" == "true" || "$CLEANUP_ALL" == "true" ]]; then
        cleanup_aws_resources
    fi
    
    if [[ "$CLEANUP_LOCAL" == "true" || "$CLEANUP_ALL" == "true" ]]; then
        cleanup_local_files
    fi
    
    show_cleanup_summary
}

# Run main function with all arguments
main "$@"
