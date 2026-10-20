
def call(Map configMap = [:]) {
    def gitopsRepo = configMap.get('gitopsRepo') ?:
        'https://github.com/BhavyaPriyanka/Gitops-ArgoCD.git'

    def gitopsBranch = configMap.get('gitopsBranch') ?: 'main'
    def credentialsId = configMap.get('gitopsCredentialsId') ?: 'github-credentials'
    def valuesFile = configMap.get('valuesFile') ?: 'apps/backend/helm/values.yaml'
    def ecrImage = configMap.get('ecrImage') ?:
        '837206354502.dkr.ecr.us-east-1.amazonaws.com/localhelp-backend'

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
                description: 'Exact backend image tag produced by the successful CI build'
            )
        }

        environment {
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

                        if (!tag) {
                            error 'IMAGE_TAG is required. Pass the tag produced by CI.'
                        }

                        if (!(tag ==~ /[A-Za-z0-9_.-]+/)) {
                            error "Invalid image tag: ${tag}"
                        }

                        env.DEPLOY_IMAGE_TAG = tag

                        echo "Image: ${env.ECR_IMAGE}:${env.DEPLOY_IMAGE_TAG}"
                        echo "GitOps repository: ${env.GITOPS_REPO}"
                        echo "GitOps branch: ${env.GITOPS_BRANCH}"
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

                            echo "Current backend image configuration:"
                            grep -nE '^[[:space:]]*(image|imageVersion):' "$VALUES_FILE"

                            grep -Fq "$ECR_IMAGE" "$VALUES_FILE" || {
                                echo "ERROR: Expected backend ECR repository not found."
                                echo "Check $VALUES_FILE; frontend values must not be used."
                                exit 1
                            }

                            COUNT=$(grep -Ec '^[[:space:]]*imageVersion:' "$VALUES_FILE")

                            if [ "$COUNT" -ne 1 ]; then
                                echo "ERROR: Expected exactly one imageVersion entry."
                                exit 1
                            fi
                        '''
                    }
                }
            }

            stage('Update Backend Image Version') {
                steps {
                    dir('gitops') {
                        sh '''
                            set -eu

                            sed -i -E \
                              "s|^([[:space:]]*imageVersion:[[:space:]]*).*|\\1${DEPLOY_IMAGE_TAG}|" \
                              "$VALUES_FILE"

                            echo "Updated backend image configuration:"
                            grep -nE '^[[:space:]]*(image|imageVersion):' "$VALUES_FILE"

                            grep -Fq "$ECR_IMAGE" "$VALUES_FILE"
                            grep -Eq \
                              "^[[:space:]]*imageVersion:[[:space:]]*${DEPLOY_IMAGE_TAG}[[:space:]]*$" \
                              "$VALUES_FILE"
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
                                    echo "GitOps already references this image tag. No commit needed."
                                    exit 0
                                fi

                                git add "$VALUES_FILE"

                                git commit -m \
                                  "chore(backend): update image to ${DEPLOY_IMAGE_TAG}"

                                # Authenticate without putting the token in the Git URL.
                                ASKPASS_FILE="$(pwd)/.git-askpass.sh"

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

                                rm -f "$ASKPASS_FILE"

                                echo "GitOps update pushed successfully."
                            '''
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "CD handoff successful: ${env.ECR_IMAGE}:${env.DEPLOY_IMAGE_TAG}"
                echo "GitOps was updated. Argo CD is responsible for syncing the deployment."
            }

            failure {
                echo 'CD handoff failed. Check the GitOps checkout, credentials, and image configuration.'
            }

            always {
                deleteDir()
            }
        }
    }
}
