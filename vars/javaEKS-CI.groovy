
def call(Map configMap) {
    def appDir = configMap.get("appDir") ?: "backend"
    def project = configMap.get("project") ?: "localhelp"
    def component = configMap.get("component") ?: "localhelp-backend"
    def sonarProjectKey = configMap.get("sonarProjectKey") ?: "localhelp-backend"

    pipeline {
        agent {
            label 'AGENT-1'
        }

        options {
            timestamps()
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        environment {
            AWS_REGION = 'us-east-1'
            AWS_ACCOUNT_ID = '837206354502'
            ECR_REPOSITORY = 'localhelp-backend'
            ARTIFACT_BUCKET = 'localhelp-backend-artifacts'
            APP_DIR = "${appDir}"
            COMPONENT = "${component}"
            PROJECT_NAME = "${project}"
            SONAR_PROJECT_KEY = "${sonarProjectKey}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm

                    script {
                        def gitTag = sh(
                            script: 'git describe --tags --exact-match 2>/dev/null || true',
                            returnStdout: true
                        ).trim()

                        def commit = sh(
                            script: 'git rev-parse --short=8 HEAD',
                            returnStdout: true
                        ).trim()

                        def branch = (env.BRANCH_NAME ?: 'manual')
                            .replaceAll(/[^A-Za-z0-9_.-]/, '-')
                            .toLowerCase()

                        env.BUILD_VERSION = (
                            gitTag
                                ? gitTag.replaceFirst(/^v/, '')
                                : "${branch}-${env.BUILD_NUMBER}-${commit}"
                        ).toLowerCase().replaceAll(/[^a-z0-9_.-]/, '-')

                        echo "Component: ${env.COMPONENT}"
                        echo "Build version: ${env.BUILD_VERSION}"
                        echo "Commit: ${commit}"
                    }

                    sh '''
                        set -eu
                        test -f "$APP_DIR/pom.xml"
                        test -f "$APP_DIR/Dockerfile"
                        java -version
                        mvn -version
                        aws --version
                        docker --version
                    '''
                }
            }

            stage('Compile and Unit Tests') {
                steps {
                    dir("${env.APP_DIR}") {
                        sh '''
                            set -eu
                            mvn -B clean verify
                        '''
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    dir("${env.APP_DIR}") {
                        withSonarQubeEnv('SonarQube') {
                            sh '''
                                set -eu
                                mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                                  -Dsonar.projectKey="$SONAR_PROJECT_KEY" \
                                  -Dsonar.projectName="$COMPONENT" \
                                  -Dsonar.projectVersion="$BUILD_VERSION"
                            '''
                        }
                    }
                }
            }

            stage('SonarQube Quality Gate') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        script {
                            def qualityGate = waitForQualityGate()
                            if (qualityGate.status != 'OK') {
                                error "SonarQube Quality Gate failed: ${qualityGate.status}"
                            }
                            echo "SonarQube Quality Gate: ${qualityGate.status}"
                        }
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    script {
                        env.ECR_IMAGE =
                            "${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.ECR_REPOSITORY}"

                        sh '''
                            set -eu
                            docker build \
                              --label "org.opencontainers.image.version=$BUILD_VERSION" \
                              --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" \
                              -t "$ECR_IMAGE:$BUILD_VERSION" \
                              "$APP_DIR"
                        '''
                    }
                }
            }

            stage('Push Image to ECR') {
                steps {
                    sh '''
                        set -eu

                        aws sts get-caller-identity

                        aws ecr get-login-password --region "$AWS_REGION" |
                          docker login \
                            --username AWS \
                            --password-stdin \
                            "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

                        docker push "$ECR_IMAGE:$BUILD_VERSION"

                        docker logout \
                          "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
                    '''
                }
            }

            stage('Publish Build Artifacts to S3') {
                steps {
                    dir("${env.APP_DIR}") {
                        sh '''
                            set -eu

                            JAR_FILE=$(find target -maxdepth 1 -type f \
                              -name '*.jar' \
                              ! -name '*-sources.jar' \
                              ! -name '*-javadoc.jar' \
                              ! -name 'original-*.jar' \
                              | head -n 1)

                            if [ -z "$JAR_FILE" ]; then
                              echo "No packaged application JAR found"
                              exit 1
                            fi

                            aws s3 cp "$JAR_FILE" \
                              "s3://$ARTIFACT_BUCKET/backend/$BUILD_VERSION/$(basename "$JAR_FILE")"

                            if [ -f db/init.sql ]; then
                              aws s3 cp db/init.sql \
                                "s3://$ARTIFACT_BUCKET/backend/$BUILD_VERSION/init.sql"
                            fi
                        '''
                    }
                }
            }
        }

        post {
            success {
                echo "CI SUCCESS: ${env.COMPONENT}:${env.BUILD_VERSION}"
                echo "Image: ${env.ECR_IMAGE}:${env.BUILD_VERSION}"
                echo "Deployment is handled separately by Argo CD."
            }

            failure {
                echo "CI FAILED: ${env.COMPONENT}"
            }

            always {
                sh 'docker image prune -f || true'
                deleteDir()
            }
        }
    }
}
