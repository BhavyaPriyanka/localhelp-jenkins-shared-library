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
                    script {
                        def scannerHome = tool(
                            name: 'SonarScanner',
                            type: 'hudson.plugins.sonar.SonarRunnerInstallation'
                        )

                        withSonarQubeEnv('SonarQube') {
                            withEnv(["PATH+SONAR_SCANNER=${scannerHome}/bin"]) {
                                dir('frontend') {
                                    sh '''
                                        set -eu
                                        echo "===== FRONTEND SONARQUBE ANALYSIS ====="

                                        test -d src || {
                                            echo "ERROR: frontend/src directory not found"
                                            exit 1
                                        }

                                        sonar-scanner \
                                            -Dsonar.projectKey=localhelp-frontend \
                                            -Dsonar.projectName=localhelp-frontend \
                                            -Dsonar.projectVersion="${IMAGE_VERSION}" \
                                            -Dsonar.sources=src \
                                            -Dsonar.exclusions="node_modules/**,build/**,dist/**,coverage/**" \
                                            -Dsonar.sourceEncoding=UTF-8

                                        test -f .scannerwork/report-task.txt
                                        echo "===== SONARQUBE ANALYSIS SUBMITTED ====="
                                    '''
                                }
                            }
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

                                    test -f "$TASK_FILE" || {
                                        echo "ERROR: Sonar report-task.txt not found"
                                        exit 1
                                    }

                                    CE_TASK_URL=$(awk -F= '$1 == "ceTaskUrl" {print substr($0, index($0, "=") + 1); exit}' "$TASK_FILE")

                                    test -n "$CE_TASK_URL" || {
                                        echo "ERROR: ceTaskUrl not found"
                                        exit 1
                                    }

                                    STATUS="PENDING"
                                    RESPONSE=""

                                    for i in $(seq 1 30); do
                                        if RESPONSE=$(curl -fsS \
                                            --connect-timeout 10 \
                                            --max-time 30 \
                                            -u "$SONAR_TOKEN:" \
                                            "$CE_TASK_URL"); then

                                            STATUS=$(printf '%s' "$RESPONSE" |
                                                awk -F'"' '{
                                                    for (i = 1; i < NF; i++) {
                                                        if ($i == "status" && $(i + 1) == ":") {
                                                            print $(i + 2)
                                                            exit
                                                        }
                                                    }
                                                }')

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
                                        awk -F'"' '{
                                            for (i = 1; i < NF; i++) {
                                                if ($i == "analysisId" && $(i + 1) == ":") {
                                                    print $(i + 2)
                                                    exit
                                                }
                                            }
                                        }')

                                    test -n "$ANALYSIS_ID" || {
                                        echo "ERROR: Analysis ID not found"
                                        exit 1
                                    }

                                    GATE_RESPONSE=$(curl -fsS \
                                        --connect-timeout 10 \
                                        --max-time 30 \
                                        -u "$SONAR_TOKEN:" \
                                        "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID")

                                    GATE=$(printf '%s' "$GATE_RESPONSE" |
                                        awk -F'"' '{
                                            in_project_status = 0

                                            for (i = 1; i < NF; i++) {
                                                if ($i == "projectStatus" && $(i + 1) == ":") {
                                                    in_project_status = 1
                                                } else if (in_project_status && $i == "status" && $(i + 1) == ":") {
                                                    print $(i + 2)
                                                    exit
                                                }
                                            }
                                        }')

                                    echo "SonarQube Quality Gate: $GATE"

                                    if [ "$GATE" != "OK" ]; then
                                        echo "ERROR: Quality Gate failed or status could not be parsed"
                                        exit 1
                                    fi

                                    echo "===== FRONTEND QUALITY GATE PASSED ====="
                                '''
                            }
                        }
                    }
                }
            }

            stage('Build Frontend Application') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            echo "===== BUILDING FRONTEND APPLICATION ====="

                            npm run build

                            if [ -d dist ]; then
                                echo "Build output found: dist/"
                            elif [ -d build ]; then
                                echo "Build output found: build/"
                            else
                                echo "ERROR: Build completed but neither dist/ nor build/ exists"
                                echo "Check the output directory configured by package.json"
                                exit 1
                            fi

                            echo "===== FRONTEND APPLICATION BUILD COMPLETE ====="
                        '''
                    }
                }
            }

            stage('Build Frontend Image') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            IMAGE_URI="${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${IMAGE_VERSION}"

                            echo "===== BUILDING FRONTEND IMAGE ====="

                            test -f Dockerfile || {
                                echo "ERROR: frontend/Dockerfile not found"
                                exit 1
                            }

                            docker build -t "$IMAGE_URI" .
                            docker image inspect "$IMAGE_URI"

                            echo "Built image: $IMAGE_URI"
                            echo "===== FRONTEND IMAGE BUILT ====="
                        '''
                    }
                }
            }

            stage('Package Frontend Artifact') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -eu

                            echo "===== PACKAGING FRONTEND ARTIFACT ====="

                            if [ -d dist ]; then
                                BUILD_DIR="dist"
                            elif [ -d build ]; then
                                BUILD_DIR="build"
                            else
                                echo "ERROR: Neither dist/ nor build/ exists"
                                exit 1
                            fi

                            command -v zip >/dev/null 2>&1 || {
                                echo "ERROR: zip utility is not installed on the Jenkins agent"
                                exit 1
                            }

                            ARTIFACT="frontend-${IMAGE_VERSION}.zip"

                            rm -f "$ARTIFACT"
                            zip -rq "$ARTIFACT" "$BUILD_DIR"

                            test -s "$ARTIFACT"

                            echo "Created artifact: $ARTIFACT"
                            ls -lh "$ARTIFACT"
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

                        docker image inspect "$IMAGE_URI"
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

            stage('Trigger Frontend CD') {
                steps {
                    script {
                        echo "Triggering frontend CD for image tag: ${env.IMAGE_VERSION}"

                        build job: 'frontend-CD',
                            wait: true,
                            propagate: true,
                            parameters: [
                                string(
                                    name: 'IMAGE_TAG',
                                    value: env.IMAGE_VERSION
                                )
                            ]
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