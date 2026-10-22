def call(Map configMap) {

    def version

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
                        version = env.BUILD_NUMBER
                        env.IMAGE_VERSION = version
                    }

                    echo "Application: ${env.APP_NAME}"
                    echo "Image version: ${env.IMAGE_VERSION}"
                }
            }

            stage('Install Dependencies') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -e

                            echo "===== INSTALLING NPM DEPENDENCIES ====="

                            test -f package.json
                            test -f package-lock.json

                            npm ci

                            echo "===== DEPENDENCIES INSTALLED ====="
                        '''
                    }
                }
            }

            stage('Build React Application') {
                steps {
                    dir('frontend') {
                        sh '''
                            set -e

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
                            set -e

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
                            set -e

                            IMAGE_URI="${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${IMAGE_VERSION}"

                            echo "===== BUILDING FRONTEND DOCKER IMAGE ====="
                            echo "IMAGE: $IMAGE_URI"

                            test -f Dockerfile

                            docker build --no-cache \
                                -t "$IMAGE_URI" \
                                .

                            echo "===== DOCKER IMAGE CREATED ====="

                            docker image inspect "$IMAGE_URI" \
                                --format='Image ID: {{.Id}}'
                        '''
                    }
                }
            }

            stage('ECR Login') {
                steps {
                    sh '''
                        set -e

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
                        set -e

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

                        if [ -z "$IMAGE_DIGEST" ] || [ "$IMAGE_DIGEST" = "None" ]; then
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
                            set -e

                            ARTIFACT="frontend-${IMAGE_VERSION}.zip"
                            S3_PATH="s3://localhelp-frontend-artifacts/frontend/${IMAGE_VERSION}/${ARTIFACT}"

                            echo "===== UPLOADING FRONTEND ARTIFACT TO S3 ====="

                            test -s "$ARTIFACT"

                            aws s3 cp "$ARTIFACT" "$S3_PATH"

                            echo "===== VERIFYING S3 ARTIFACT ====="

                            aws s3 ls \
                                "s3://localhelp-frontend-artifacts/frontend/${IMAGE_VERSION}/" \
                                --recursive

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