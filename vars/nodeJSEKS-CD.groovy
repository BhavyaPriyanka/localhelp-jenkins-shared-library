def call(Map configMap = [:]) {
    def gitopsRepo = configMap.get('gitopsRepo') ?:
        'https://github.com/BhavyaPriyanka/Gitops-ArgoCD.git'
    def gitopsBranch = configMap.get('gitopsBranch') ?: 'main'
    def credentialsId = configMap.get('gitopsCredentialsId') ?: 'github-credentials'
    def valuesFile = configMap.get('valuesFile') ?: 'apps/frontend/helm/values.yaml'
    def ecrImage = configMap.get('ecrImage') ?:
        '837206354502.dkr.ecr.us-east-1.amazonaws.com/localhelp-frontend'

    pipeline {
        agent {
            label 'AGENT-1'
        }

        options {
            timestamps()
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        parameters {
            string(
                name: 'IMAGE_TAG',
                defaultValue: '',
                description: 'Exact frontend image tag produced by the successful CI build'
            )
        }

        environment {
            AWS_REGION = 'us-east-1'
            TARGET_GROUP_NAME = 'localhelp-dev-frontend'
            GITOPS_REPO = "${gitopsRepo}"
            GITOPS_BRANCH = "${gitopsBranch}"
            GITOPS_CREDENTIALS_ID = "${credentialsId}"
            VALUES_FILE = "${valuesFile}"
            ECR_IMAGE = "${ecrImage}"
        }

        stages {
            stage('Validate Release') {
                steps {
                    script {
                        def tag = params.IMAGE_TAG?.trim()

                        if (!tag || !(tag ==~ /[A-Za-z0-9_.-]+/)) {
                            error 'Provide a valid IMAGE_TAG from the successful frontend CI build.'
                        }

                        env.DEPLOY_IMAGE_TAG = tag
                        echo "Frontend image: ${env.ECR_IMAGE}:${env.DEPLOY_IMAGE_TAG}"
                    }
                }
            }

            stage('Discover Target Group ARN') {
                steps {
                    script {
                        env.TARGET_GROUP_ARN = sh(
                            script: '''
                                set -eu
                                aws elbv2 describe-target-groups \
                                  --names "$TARGET_GROUP_NAME" \
                                  --region "$AWS_REGION" \
                                  --query 'TargetGroups[0].TargetGroupArn' \
                                  --output text
                            ''',
                            returnStdout: true
                        ).trim()

                        if (!(env.TARGET_GROUP_ARN ==~
                            /arn:aws:elasticloadbalancing:us-east-1:[0-9]{12}:targetgroup\/[A-Za-z0-9-]+\/[A-Fa-f0-9]+/)) {
                            error "Could not resolve a valid Target Group ARN: ${env.TARGET_GROUP_ARN}"
                        }

                        echo "Discovered frontend Target Group ARN: ${env.TARGET_GROUP_ARN}"
                    }
                }
            }

            stage('Checkout GitOps Repository') {
                steps {
                    dir('gitops') {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: "*/${env.GITOPS_BRANCH}"]],
                            userRemoteConfigs: [[
                                url: env.GITOPS_REPO,
                                credentialsId: env.GITOPS_CREDENTIALS_ID
                            ]]
                        ])

                        sh '''
                            set -eu
                            test -f "$VALUES_FILE"

                            grep -Fq "$ECR_IMAGE" "$VALUES_FILE" || {
                                echo "ERROR: Frontend ECR repository does not match."
                                exit 1
                            }

                            test "$(grep -Ec '^[[:space:]]*imageVersion:' "$VALUES_FILE")" -eq 1
                            test "$(grep -Ec '^[[:space:]]*arn:' "$VALUES_FILE")" -eq 1
                        '''
                    }
                }
            }

            stage('Update Frontend GitOps Values') {
                steps {
                    dir('gitops') {
                        sh '''
                            set -eu
                            export DEPLOY_IMAGE_TAG TARGET_GROUP_ARN

                            python3 - <<'PY'
import os
from pathlib import Path

path = Path(os.environ["VALUES_FILE"])
lines = path.read_text().splitlines()

image_count = 0
arn_count = 0
updated = []

for line in lines:
    stripped = line.lstrip()

    if stripped.startswith("imageVersion:"):
        indent = line[:len(line) - len(stripped)]
        line = indent + "imageVersion: " + os.environ["DEPLOY_IMAGE_TAG"]
        image_count += 1

    elif stripped.startswith("arn:"):
        indent = line[:len(line) - len(stripped)]
        line = indent + 'arn: "' + os.environ["TARGET_GROUP_ARN"] + '"'
        arn_count += 1

    updated.append(line)

if image_count != 1:
    raise SystemExit(f"Expected exactly one imageVersion entry, found {image_count}")

if arn_count != 1:
    raise SystemExit(f"Expected exactly one targetGroup ARN entry, found {arn_count}")

path.write_text("\\n".join(updated) + "\\n")
PY

                            echo 'Updated frontend values:'
                            cat "$VALUES_FILE"

                            grep -Fq "$ECR_IMAGE" "$VALUES_FILE"
                            grep -Fq "$DEPLOY_IMAGE_TAG" "$VALUES_FILE"
                            grep -Fq "$TARGET_GROUP_ARN" "$VALUES_FILE"
                        '''
                    }
                }
            }

            stage('Commit and Push GitOps Change') {
                steps {
                    dir('gitops') {
                        withCredentials([
                            usernamePassword(
                                credentialsId: env.GITOPS_CREDENTIALS_ID,
                                usernameVariable: 'GIT_USERNAME',
                                passwordVariable: 'GIT_TOKEN'
                            )
                        ]) {
                            sh '''
                                set -eu

                                git config user.name "localhelp-ci"
                                git config user.email "localhelp-ci@users.noreply.github.com"

                                if git diff --quiet -- "$VALUES_FILE"; then
                                    echo "GitOps already has this image tag and ARN."
                                    exit 0
                                fi

                                git add "$VALUES_FILE"
                                git commit -m "chore(frontend): update image to ${DEPLOY_IMAGE_TAG}"

                                ASKPASS_FILE="$(pwd)/.git-askpass.sh"
                                trap 'rm -f "$ASKPASS_FILE"' EXIT

                                cat > "$ASKPASS_FILE" <<'ASKPASS'
#!/bin/sh
case "$1" in
    *Username*) printf '%s\\n' "$GIT_USERNAME" ;;
    *Password*) printf '%s\\n' "$GIT_TOKEN" ;;
    *) exit 1 ;;
esac
ASKPASS

                                chmod 700 "$ASKPASS_FILE"
                                export GIT_ASKPASS="$ASKPASS_FILE"
                                export GIT_TERMINAL_PROMPT=0

                                git push origin "$GITOPS_BRANCH"
                                echo "Frontend GitOps update pushed successfully."
                            '''
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Frontend GitOps handoff complete for ${env.ECR_IMAGE}:${env.DEPLOY_IMAGE_TAG}"
                echo 'Argo CD remains responsible for deployment.'
            }

            failure {
                echo 'Frontend CD failed. Check AWS permissions, GitOps credentials, and the values file.'
            }

            always {
                deleteDir()
            }
        }
    }
}
