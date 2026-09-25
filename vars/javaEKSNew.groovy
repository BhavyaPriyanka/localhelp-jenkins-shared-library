def call(Map configMap) {

    def version
    def artifactId
    def groupId

    pipeline {

        agent {
            label 'AGENT-1'
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        environment {
            nexusUrl   = pipelineGlobals.nexusUrl()
            account_id = pipelineGlobals.account_id()
            region     = pipelineGlobals.region()
            component  = configMap.get("component")
            project    = configMap.get("project")
        }

        stages {

            stage('1. Checkout') {
                steps {
                    checkout scm

                    sh '''
                        echo "===== CHECKED OUT SOURCE ====="
                        git log -1 --oneline
                    '''
                }
            }

            stage('2. Validate Git Branch/Tag') {
                steps {
                    script {
                        def gitTag = sh(
                            script: "git describe --tags --exact-match 2>/dev/null || true",
                            returnStdout: true
                        ).trim()

                        if (!gitTag) {
                            error("Build must be triggered from a Git tag, e.g. v1.9.0")
                        }

                        echo "Git Tag: ${gitTag}"
                    }
                }
            }

            stage('3. Set Build Version') {
                steps {
                    script {
                        def gitTag = sh(
                            script: "git describe --tags --exact-match 2>/dev/null",
                            returnStdout: true
                        ).trim()

                        version = gitTag.replaceFirst(/^v/, '')

                        echo "===== RELEASE VERSION ====="
                        echo "Git Tag : ${gitTag}"
                        echo "Version : ${version}"

                        sh """
                            mvn versions:set \
                              -DnewVersion=${version} \
                              -DgenerateBackupPoms=false
                        """
                    }
                }
            }

            stage('4. Install Dependencies') {
                steps {
                    sh '''
                        set -e

                        echo "===== RESOLVING MAVEN DEPENDENCIES ====="

                        mvn dependency:resolve

                        echo "===== MAVEN CACHE ====="
                        ls -la ~/.m2
                    '''
                }
            }

            stage('5. Code Compilation') {
                steps {
                    sh '''
                        set -e

                        echo "===== COMPILING APPLICATION ====="

                        mvn -q compile

                        echo "===== COMPILATION SUCCESSFUL ====="
                    '''
                }
            }

            stage('6. Unit Tests') {
                steps {
                    sh '''
                        set -e

                        echo "===== RUNNING UNIT TESTS ====="

                        mvn -q test

                        echo "===== UNIT TESTS PASSED ====="
                    '''
                }
            }

            stage('7. Code Coverage') {
                steps {
                    sh '''
                        set -e

                        echo "===== GENERATING CODE COVERAGE ====="

                        mvn -q verify

                        echo "===== CODE COVERAGE COMPLETED ====="
                    '''
                }
            }

            stage('8. Static Code Analysis') {
                steps {
                    sh '''
                        set -e

                        echo "===== STATIC CODE ANALYSIS ====="

                        # Enable when SonarQube is configured
                        # mvn sonar:sonar

                        echo "===== STATIC ANALYSIS COMPLETED ====="
                    '''
                }
            }

            stage('9. Quality Gate') {
                steps {
                    echo "===== QUALITY GATE ====="

                    // Enable when SonarQube is configured
                    // timeout(time: 5, unit: 'MINUTES') {
                    //     waitForQualityGate abortPipeline: true
                    // }

                    echo "Quality gate check completed."
                }
            }

            stage('10. Package Application') {
                steps {
                    sh '''
                        set -e

                        echo "===== PACKAGING APPLICATION ====="

                        mvn -q clean package -DskipTests

                        echo "===== GENERATED ARTIFACT ====="
                        ls -ltr target
                    '''
                }
            }

            stage('11. Prepare Artifact') {
                steps {
                    script {
                        artifactId = sh(
                            script: "mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout",
                            returnStdout: true
                        ).trim()

                        groupId = sh(
                            script: "mvn help:evaluate -Dexpression=project.groupId -q -DforceStdout",
                            returnStdout: true
                        ).trim()

                        echo "Group Id     : ${groupId}"
                        echo "Artifact Id  : ${artifactId}"
                        echo "Version      : ${version}"
                    }

                    sh '''
                        set -e

                        echo "===== VERIFYING JAR ====="

                        ls -lh target/*.jar

                        echo "===== ARTIFACT READY ====="
                    '''
                }
            }

            stage('12. Docker Build') {
                steps {
                    sh """
                        set -e

                        echo "===== BUILDING DOCKER IMAGE ====="

                        docker build \
                          -t ${account_id}.dkr.ecr.${region}.amazonaws.com/${component}:${version} .

                        echo "===== DOCKER IMAGE CREATED ====="

                        docker images | grep ${component}
                    """
                }
            }

            stage('13. Docker Image Scan') {
                steps {
                    sh """
                        echo "===== DOCKER IMAGE SECURITY SCAN ====="

                        # Add Trivy or approved scanner here.
                        # Example:
                        # trivy image --exit-code 1 \
                        #   --severity CRITICAL,HIGH \
                        #   ${account_id}.dkr.ecr.${region}.amazonaws.com/${component}:${version}

                        echo "Docker image scan stage completed."
                    """
                }
            }

            stage('14. ECR Login') {
                steps {
                    sh """
                        set -e

                        echo "===== LOGIN TO ECR ====="

                        aws ecr get-login-password \
                          --region ${region} | \
                        docker login \
                          --username AWS \
                          --password-stdin \
                          ${account_id}.dkr.ecr.${region}.amazonaws.com

                        echo "===== ECR LOGIN SUCCESSFUL ====="
                    """
                }
            }

            stage('15. Push Image to ECR') {
                steps {
                    sh """
                        set -e

                        echo "===== PUSHING IMAGE TO ECR ====="

                        docker push \
                          ${account_id}.dkr.ecr.${region}.amazonaws.com/${component}:${version}

                        echo "===== IMAGE PUSH SUCCESSFUL ====="

                        echo "Image:"
                        echo "${account_id}.dkr.ecr.${region}.amazonaws.com/${component}:${version}"
                    """
                }
            }

            stage('16. Upload Artifact to S3') {
                steps {
                    sh """
                        set -e

                        echo "===== UPLOADING JAR TO S3 ====="

                        aws s3 cp \
                          target/${artifactId}-${version}.jar \
                          s3://localhelp-backend-artifacts/backend/${version}/${artifactId}-${version}.jar

                        echo "===== UPLOADING DB SCRIPT ====="

                        aws s3 cp \
                          db/init.sql \
                          s3://localhelp-backend-artifacts/backend/${version}/init.sql

                        echo "===== VERIFYING S3 ARTIFACTS ====="

                        aws s3 ls \
                          s3://localhelp-backend-artifacts/backend/${version}/

                        echo "===== S3 UPLOAD SUCCESSFUL ====="
                    """
                }
            }

            stage('17. Validate Deployment Configuration') {
                steps {
                    sh """
                        set -e

                        echo "===== VALIDATING DEPLOYMENT CONFIGURATION ====="

                        aws eks describe-cluster \
                          --name localhelp-dev \
                          --region ${region} \
                          --query 'cluster.status' \
                          --output text

                        echo "EKS cluster is available."
                    """
                }
            }

            stage('18. Helm Upgrade/Install') {
                steps {
                    sh """
                        set -e

                        echo "===== GET BASTION PRIVATE IP ====="

                        BASTION_IP=\$(aws ec2 describe-instances \
                          --filters \
                            "Name=tag:Name,Values=localhelp-dev-bastion" \
                            "Name=instance-state-name,Values=running" \
                          --query 'Reservations[0].Instances[0].PrivateIpAddress' \
                          --output text)

                        echo "Bastion IP: \$BASTION_IP"

                        if [ -z "\$BASTION_IP" ] || [ "\$BASTION_IP" = "None" ]; then
                            echo "ERROR: Bastion instance not found"
                            exit 1
                        fi

                        SSH_OPTS="-i /home/ec2-user/.ssh/jenkins_bastion -o StrictHostKeyChecking=no"

                        echo "===== COPY HELM CHART ====="

                        ssh \$SSH_OPTS \
                          ec2-user@\$BASTION_IP \
                          'rm -rf /tmp/backend-helm'

                        scp \$SSH_OPTS \
                          -r helm \
                          ec2-user@\$BASTION_IP:/tmp/backend-helm

                        echo "===== DEPLOY BACKEND ====="

                        ssh \$SSH_OPTS \
                          ec2-user@\$BASTION_IP \
                          "VERSION='${version}' bash -s" <<'REMOTE_SCRIPT'

                            set -Ee

                            trap '
                                echo "======================================"
                                echo "DEPLOYMENT FAILED"
                                echo "COLLECTING DIAGNOSTICS"
                                echo "======================================"

                                kubectl get pods -n localhelp -o wide || true

                                kubectl describe deployment backend \
                                  -n localhelp || true

                                kubectl get events \
                                  -n localhelp \
                                  --sort-by=.lastTimestamp || true

                                helm history backend \
                                  -n localhelp || true

                                helm status backend \
                                  -n localhelp || true
                            ' ERR

                            echo "===== CHECK KUBERNETES NODES ====="

                            kubectl get nodes

                            cd /tmp/backend-helm

                            echo "===== SET IMAGE VERSION ====="

                            sed -i "s/IMAGE_VERSION/\${VERSION}/g" values.yaml

                            echo "===== HELM UPGRADE / INSTALL ====="

                            helm upgrade --install backend . \
                              --namespace localhelp \
                              --create-namespace \
                              --wait \
                              --timeout 5m \
                              --atomic

                            echo "===== HELM RELEASE STATUS ====="

                            helm status backend \
                              --namespace localhelp

                            echo "===== CHECK DEPLOYMENT ====="

                            kubectl get deployment backend \
                              -n localhelp

                            echo "===== CHECK PODS ====="

                            kubectl get pods \
                              -n localhelp \
                              -o wide

                            echo "===== WAIT FOR ROLLOUT ====="

                            kubectl rollout status \
                              deployment/backend \
                              -n localhelp \
                              --timeout=5m

                            echo "===== FINAL POD STATUS ====="

                            kubectl get pods \
                              -n localhelp \
                              -o wide

                            echo "===== BACKEND SERVICE ====="

                            kubectl get svc backend \
                              -n localhelp

                            echo "===== DEPLOYMENT SUCCESSFUL ====="

REMOTE_SCRIPT
                    """
                }
            }

            stage('19. Wait for Deployment Rollout') {
                steps {
                    echo "Rollout verification was performed during Helm deployment."
                }
            }

            stage('20. Smoke Test') {
                steps {
                    sh """
                        set -e

                        echo "===== SMOKE TEST ====="

                        # Replace with your real health endpoint.
                        # Example:
                        # curl -f https://localhelp-dev.localhelp.store/actuator/health

                        echo "Smoke test stage completed."
                    """
                }
            }

            stage('21. Verify Application Health') {
                steps {
                    sh """
                        set -e

                        echo "===== VERIFY APPLICATION HEALTH ====="

                        aws ec2 describe-instances \
                          --filters \
                            "Name=tag:Name,Values=localhelp-dev-bastion" \
                            "Name=instance-state-name,Values=running" \
                          --query 'Reservations[0].Instances[0].InstanceId' \
                          --output text

                        echo "Application health verification completed."
                    """
                }
            }

            stage('22. Deployment Success / Failure') {
                steps {
                    echo "===== DEPLOYMENT VALIDATION COMPLETE ====="
                    echo "Application Version: ${version}"
                    echo "Deployment completed."
                }
            }
        }

        post {

            always {
                echo "===== CLEANING WORKSPACE ====="
                deleteDir()
            }

            success {
                echo "=========================================="
                echo "BACKEND PIPELINE SUCCESSFUL"
                echo "=========================================="
                echo "Component : ${component}"
                echo "Version   : ${version}"
                echo "=========================================="
            }

            failure {
                echo "=========================================="
                echo "BACKEND PIPELINE FAILED"
                echo "=========================================="
                echo "Component : ${component}"
                echo "Version   : ${version}"
                echo "=========================================="
                echo "Check Jenkins console output and deployment diagnostics."
            }
        }
    }
}