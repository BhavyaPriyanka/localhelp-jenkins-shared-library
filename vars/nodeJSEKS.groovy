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

            stage('Install Dependencies') {
                steps {
                    sh '''
                        set -e

                        echo "===== INSTALLING NPM DEPENDENCIES ====="

                        npm ci

                        echo "===== DEPENDENCIES INSTALLED ====="
                    '''
                }
            }

            stage('Build React Application') {
                steps {
                    sh '''
                        set -e

                        echo "===== BUILDING REACT APPLICATION ====="

                        npm run build

                        echo "===== VERIFYING BUILD OUTPUT ====="

                        test -d build
                        test -n "$(ls -A build)"

                        ls -lh build

                        echo "===== REACT BUILD SUCCESSFUL ====="
                    '''
                }
            }

            stage('Prepare Artifact') {
                steps {
                    script {
                        version = env.BUILD_NUMBER
                    }

                    sh '''
                        set -e

                        echo "===== PREPARING FRONTEND ARTIFACT ====="

                        zip -r frontend-${BUILD_NUMBER}.zip build

                        test -s frontend-${BUILD_NUMBER}.zip

                        ls -lh frontend-${BUILD_NUMBER}.zip

                        echo "===== ARTIFACT CREATED ====="
                    '''
                }
            }

            stage('Docker Build') {
                steps {
                    sh '''
                        set -e

                        echo "===== BUILDING FRONTEND DOCKER IMAGE ====="

                        docker build --no-cache \
                            -t "${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${BUILD_NUMBER}" \
                            .

                        echo "===== DOCKER IMAGE CREATED ====="

                        docker images \
                            "${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${BUILD_NUMBER}"
                    '''
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

                        IMAGE_URI="${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${BUILD_NUMBER}"

                        echo "===== PUSHING FRONTEND IMAGE ====="

                        docker push "$IMAGE_URI"

                        echo "===== VERIFYING IMAGE IN ECR ====="

                        aws ecr describe-images \
                            --region "$region" \
                            --repository-name "$ECR_REPO" \
                            --image-ids imageTag="$BUILD_NUMBER" \
                            --query 'imageDetails[0].imageDigest' \
                            --output text

                        echo "IMAGE: $IMAGE_URI"

                        echo "===== ECR PUSH SUCCESSFUL ====="
                    '''
                }
            }

            stage('Upload Artifact to S3') {
                steps {
                    sh '''
                        set -e

                        echo "===== UPLOADING FRONTEND ARTIFACT TO S3 ====="

                        aws s3 cp \
                            "frontend-${BUILD_NUMBER}.zip" \
                            "s3://localhelp-frontend-artifacts/frontend/${BUILD_NUMBER}/frontend-${BUILD_NUMBER}.zip"

                        echo "===== VERIFYING S3 ARTIFACT ====="

                        aws s3 ls \
                            "s3://localhelp-frontend-artifacts/frontend/${BUILD_NUMBER}/"

                        echo "===== S3 UPLOAD SUCCESSFUL ====="
                    '''
                }
            }

            stage('CI Success') {
                steps {
                    echo "======================================"
                    echo "FRONTEND CI SUCCESSFUL"
                    echo "APPLICATION: ${APP_NAME}"
                    echo "IMAGE: ${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${version}"
                    echo "VERSION: ${version}"
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
                echo "VERSION: ${version}"
                echo "======================================"
            }

            failure {
                echo "======================================"
                echo "FRONTEND CI PIPELINE FAILED"
                echo "VERSION: ${version}"
                echo "======================================"
            }
        }
    }
}