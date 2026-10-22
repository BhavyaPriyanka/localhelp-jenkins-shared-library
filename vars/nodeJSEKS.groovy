
def call(Map configMap) {

    pipeline {

        agent {
            label 'AGENT-1'
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        environment {
            APP_NAME   = "${configMap.get('component')}"
            region     = "${pipelineGlobals.region()}"
            account_id = "${pipelineGlobals.account_id()}"
            ECR_REPO   = "${configMap.get('component')}"
        }

        stages {

            stage('Initialize Version') {
                steps {
                    script {
                        env.IMAGE_VERSION = env.BUILD_NUMBER
                    }

                    echo "Application: ${env.APP_NAME}"
                    echo "Image version: ${env.IMAGE_VERSION}"
                }
            }

            stage('Install Dependencies') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            echo "===== INSTALLING NPM DEPENDENCIES ====="

                            test -f package.json
                            test -f package-lock.json

                            npm ci

                            echo "===== DEPENDENCIES INSTALLED ====="
                        '''
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    dir('frontend') {
                        withSonarQubeEnv('SonarQube') {
                            sh '''
                                set -eu

                                echo "===== FRONTEND SONARQUBE ANALYSIS ====="

                                sonar-scanner \
                                    -Dsonar.projectKey=localhelp-frontend \
                                    -Dsonar.projectName=localhelp-frontend \
                                    -Dsonar.projectVersion="${IMAGE_VERSION}" \
                                    -Dsonar.sources=src \
                                    -Dsonar.exclusions="node_modules/**,build/**,coverage/**" \
                                    -Dsonar.sourceEncoding=UTF-8

                                test -f .scannerwork/report-task.txt

                                echo "===== SONARQUBE ANALYSIS SUBMITTED ====="
                            '''
                        }
                    }
                }
            }

            stage('SonarQube Quality Gate') {
                steps {
                    script {
                        withSonarQubeEnv('SonarQube') {
                            withCredentials([
                                string(
                                    credentialsId: 'sonarqube-token',
                                    variable: 'SONAR_TOKEN'
                                )
                            ]) {
                                sh '''
                                    set -eu

                                    TASK_FILE="frontend/.scannerwork/report-task.txt"

                                    if [ ! -f "$TASK_FILE" ]; then
                                        echo "ERROR: Sonar report-task.txt not found"
                                        exit 1
                                    fi

                                    CE_TASK_URL=$(sed -n 's/^ceTaskUrl=//p' "$TASK_FILE")

                                    if [ -z "$CE_TASK_URL" ]; then
                                        echo "ERROR: ceTaskUrl not found"
                                        exit 1
                                    fi

                                    STATUS="PENDING"
                                    RESPONSE=""

                                    for i in $(seq 1 30); do
                                        if RESPONSE=$(curl -fsS \
                                            --connect-timeout 10 \
                                            --max-time 30 \
                                            -u "$SONAR_TOKEN:" \
                                            "$CE_TASK_URL"); then

                                            STATUS=$(printf '%s' "$RESPONSE" |
                                                python3 -c '
import json,sys
print(json.load(sys.stdin)["task"]["status"])
')

                                            echo "SonarQube task status: $STATUS"

                                            if [ "$STATUS" = "SUCCESS" ]; then
                                                break
                                            fi

                                            if [ "$STATUS" = "FAILED" ] ||
                                               [ "$STATUS" = "CANCELED" ]; then
                                                echo "ERROR: SonarQube analysis task failed"
                                                exit 1
                                            fi
                                        else
                                            echo "SonarQube task request failed; retrying"
                                        fi

                                        sleep 10
                                    done

                                    if [ "$STATUS" != "SUCCESS" ]; then
                                        echo "ERROR: SonarQube analysis timed out"
                                        exit 1
                                    fi

                                    ANALYSIS_ID=$(printf '%s' "$RESPONSE" |
                                        python3 -c '
import json,sys
print(json.load(sys.stdin)["task"].get("analysisId", ""))
')

                                    if [ -z "$ANALYSIS_ID" ]; then
                                        echo "ERROR: Analysis ID not found"
                                        exit 1
                                    fi

                                    GATE_RESPONSE=$(curl -fsS \
                                        --connect-timeout 10 \
                                        --max-time 30 \
                                        -u "$SONAR_TOKEN:" \
                                        "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID")

                                    GATE=$(printf '%s' "$GATE_RESPONSE" |
                                        python3 -c '
import json,sys
print(json.load(sys.stdin)["projectStatus"]["status"])
')

                                    echo "SonarQube Quality Gate: $GATE"

                                    if [ "$GATE" != "OK" ]; then
                                        echo "ERROR: Quality Gate failed"
                                        exit 1
                                    fi

                                    echo "===== FRONTEND QUALITY GATE PASSED ====="
                                '''
                            }
                        }
                    }
                }
            }

            stage('Build React Application') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            echo "===== BUILDING REACT APPLICATION ====="

                            npm run build

                            test -d build
                            test -n "$(ls -A build)"

                            echo "===== REACT BUILD SUCCESSFUL ====="
                            ls -lh build
                        '''
                    }
                }
            }

            stage('Prepare Artifact') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            echo "===== PREPARING FRONTEND ARTIFACT ====="

                            zip -r "frontend-${IMAGE_VERSION}.zip" build

                            test -s "frontend-${IMAGE_VERSION}.zip"

                            ls -lh "frontend-${IMAGE_VERSION}.zip"

                            echo "===== ARTIFACT CREATED ====="
                        '''
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            IMAGE_URI="${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${IMAGE_VERSION}"

                            echo "===== BUILDING FRONTEND DOCKER IMAGE ====="
                            echo "IMAGE: $IMAGE_URI"

                            test -f Dockerfile

                            docker build --no-cache \
                                -t "$IMAGE_URI" \
                                .

                            docker image inspect "$IMAGE_URI" \
                                --format='Image ID: {{.Id}}'

                            echo "===== DOCKER IMAGE CREATED ====="
                        '''
                    }
                }
            }

            stage('ECR Login') {
                steps {
                    sh '''
                        set -eu

                        echo "===== LOGIN TO ECR ====="

                        aws ecr get-login-password \
                            --region "$region" |
                        docker login \
                            --username AWS \
                            --password-stdin \
                            "${account_id}.dkr.ecr.${region}.amazonaws.com"

                        echo "===== ECR LOGIN SUCCESSFUL ====="
                    '''
                }
            }

            stage('Push Image to ECR') {
                steps {
                    sh '''
                        set -eu

                        IMAGE_URI="${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${IMAGE_VERSION}"

                        echo "===== PUSHING FRONTEND IMAGE ====="
                        echo "IMAGE: $IMAGE_URI"

                        docker push "$IMAGE_URI"

                        echo "===== VERIFYING IMAGE IN ECR ====="

                        IMAGE_DIGEST=$(aws ecr describe-images \
                            --region "$region" \
                            --repository-name "$ECR_REPO" \
                            --image-ids imageTag="$IMAGE_VERSION" \
                            --query 'imageDetails[0].imageDigest' \
                            --output text)

                        if [ -z "$IMAGE_DIGEST" ] ||
                           [ "$IMAGE_DIGEST" = "None" ]; then
                            echo "ERROR: Image tag not found in ECR"
                            exit 1
                        fi

                        echo "IMAGE: $IMAGE_URI"
                        echo "DIGEST: $IMAGE_DIGEST"
                        echo "===== ECR PUSH VERIFIED ====="
                    '''
                }
            }

            stage('Upload Artifact to S3') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            ARTIFACT="frontend-${IMAGE_VERSION}.zip"
                            S3_PATH="s3://localhelp-frontend-artifacts/frontend/${IMAGE_VERSION}/${ARTIFACT}"

                            echo "===== UPLOADING FRONTEND ARTIFACT TO S3 ====="

                            test -s "$ARTIFACT"

                            aws s3 cp "$ARTIFACT" "$S3_PATH"

                            aws s3 ls \
                                "s3://localhelp-frontend-artifacts/frontend/${IMAGE_VERSION}/"

                            echo "===== S3 UPLOAD SUCCESSFUL ====="
                        '''
                    }
                }
            }

            stage('CI Success') {
                steps {
                    echo "======================================"
                    echo "FRONTEND CI SUCCESSFUL"
                    echo "APPLICATION: ${env.APP_NAME}"
                    echo "IMAGE: ${env.account_id}.dkr.ecr.${env.region}.amazonaws.com/${env.ECR_REPO}:${env.IMAGE_VERSION}"
                    echo "VERSION: ${env.IMAGE_VERSION}"
                    echo "======================================"
                }
            }
        }

        post {
            always {
                echo "===== CLEANING WORKSPACE ====="
                deleteDir()
            }

            success {
                echo "======================================"
                echo "FRONTEND CI PIPELINE SUCCESSFUL"
                echo "VERSION: ${env.IMAGE_VERSION}"
                echo "======================================"
            }

            failure {
                echo "======================================"
                echo "FRONTEND CI PIPELINE FAILED"
                echo "VERSION: ${env.IMAGE_VERSION}"
                echo "======================================"
            }
        }
    }
}
