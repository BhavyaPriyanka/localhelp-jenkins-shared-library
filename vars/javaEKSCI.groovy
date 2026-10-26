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
            timeout(time: 30, unit: 'MINUTES')
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
            CD_JOB_NAME = 'backend-CD'
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

                        env.BUILD_VERSION = (
                            gitTag
                                ? gitTag.replaceFirst(/^v/, '')
                                : "sha-${commit}"
                        ).toLowerCase().replaceAll(/[^a-z0-9_.-]/, '-')

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

       stage('Load DB Credentials') {
    steps {
        script {
            def region = 'us-east-1'
            def dbInstance = 'localhelp-dev'

            // Dynamically discover the RDS endpoint
            env.DB_HOST = sh(
                script: """
                    aws rds describe-db-instances \
                      --db-instance-identifier '${dbInstance}' \
                      --region '${region}' \
                      --query 'DBInstances[0].Endpoint.Address' \
                      --output text
                """,
                returnStdout: true
            ).trim()

            // Dynamically discover the AWS-managed secret ARN
            def secretArn = sh(
                script: """
                    aws rds describe-db-instances \
                      --db-instance-identifier '${dbInstance}' \
                      --region '${region}' \
                      --query 'DBInstances[0].MasterUserSecret.SecretArn' \
                      --output text
                """,
                returnStdout: true
            ).trim()

            if (!secretArn || secretArn == 'None' ||
                !env.DB_HOST || env.DB_HOST == 'None') {
                error('Unable to discover RDS endpoint or master secret ARN')
            }

            // Retrieve credentials without printing them to the console
            def secretJson = sh(
                script: """
                    aws secretsmanager get-secret-value \
                      --secret-id '${secretArn}' \
                      --region '${region}' \
                      --query SecretString \
                      --output text
                """,
                returnStdout: true
            ).trim()

            def credentials = new groovy.json.JsonSlurperClassic()
                .parseText(secretJson)

            env.DB_USERNAME = credentials.username
            env.DB_PASSWORD = credentials.password

            secretJson = null
            credentials = null

            echo 'RDS endpoint discovered and credentials loaded successfully.'
        }
    }
}

        stage('Initialize RDS Database') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'localhelp-rds-admin',
                        usernameVariable: 'DB_ADMIN_USER',
                        passwordVariable: 'DB_ADMIN_PASSWORD'
                    )
                ]) {
                    sh '''
                        set +x

                        mysql \
                        --host="$DB_HOST" \
                        --port=3306 \
                        --user="$DB_ADMIN_USER" \
                        --password="$DB_ADMIN_PASSWORD" \
                        < backend/src/main/resources/db/init.sql
                    '''
                }
            }
        }       

           stage('Install Dependencies') {
            steps {
                dir("${env.APP_DIR}") {
                    sh '''
                        set -eu
                        echo "Workspace: $WORKSPACE"
                        echo "APP_DIR=$APP_DIR"
                        echo "Maven directory: $(pwd)"
                        test -f pom.xml
                        mvn -B dependency:resolve
                    '''
                }
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

                        TASK_FILE=$(find . -path '*/.scannerwork/report-task.txt' \
                            -o -path '*/target/sonar/report-task.txt' \
                            | head -1)

                        if [ -z "$TASK_FILE" ]; then
                            echo "ERROR: Sonar report-task.txt not found"
                            exit 1
                        fi

                        CE_TASK_URL=$(sed -n 's/^ceTaskUrl=//p' "$TASK_FILE")

                        if [ -z "$CE_TASK_URL" ]; then
                            echo "ERROR: ceTaskUrl not found"
                            exit 1
                        fi

                        for i in $(seq 1 30); do
                            RESPONSE=$(curl -fsS -u "$SONAR_TOKEN:" "$CE_TASK_URL") || {
                                sleep 10
                                continue
                            }

                            STATUS=$(printf '%s' "$RESPONSE" |
                                python3 -c 'import json,sys; print(json.load(sys.stdin)["task"]["status"])')

                            echo "SonarQube task status: $STATUS"

                            if [ "$STATUS" = "SUCCESS" ]; then
                                break
                            fi

                            if [ "$STATUS" = "FAILED" ] || [ "$STATUS" = "CANCELED" ]; then
                                echo "ERROR: SonarQube analysis task failed"
                                exit 1
                            fi

                            sleep 10
                        done

                        if [ "$STATUS" != "SUCCESS" ]; then
                            echo "ERROR: SonarQube analysis timed out"
                            exit 1
                        fi

                        ANALYSIS_ID=$(printf '%s' "$RESPONSE" |
                            python3 -c 'import json,sys; print(json.load(sys.stdin)["task"].get("analysisId", ""))')

                        if [ -z "$ANALYSIS_ID" ]; then
                            echo "ERROR: Analysis ID not found"
                            exit 1
                        fi

                        GATE=$(curl -fsS -u "$SONAR_TOKEN:" \
                            "$SONAR_HOST_URL/api/qualitygates/project_status?analysisId=$ANALYSIS_ID" |
                            python3 -c 'import json,sys; print(json.load(sys.stdin)["projectStatus"]["status"])')

                        echo "SonarQube Quality Gate: $GATE"

                        if [ "$GATE" != "OK" ]; then
                            echo "ERROR: Quality Gate failed"
                            exit 1
                        fi
                    '''
                }
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

            stage('Trigger CD Pipeline') {
                steps {
                    script {
                        echo "Triggering CD for image: ${env.ECR_IMAGE}:${env.BUILD_VERSION}"

                        build(
                            job: env.CD_JOB_NAME,
                            wait: false,
                            parameters: [
                                string(
                                    name: 'IMAGE_TAG',
                                    value: env.BUILD_VERSION
                                )
                            ]
                        )

                        echo "CD pipeline triggered for ${env.BUILD_VERSION}"
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
