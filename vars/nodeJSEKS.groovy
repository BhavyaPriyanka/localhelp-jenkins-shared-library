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
            APP_NAME   = configMap.get("component")
            region     = pipelineGlobals.region()
            account_id = pipelineGlobals.account_id()
            ECR_REPO   = configMap.get("component")
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

                        echo "===== BUILD DIRECTORY ====="

                        ls -ltr build

                        echo "===== REACT BUILD COMPLETED ====="
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

            echo "===== ARTIFACT CREATED ====="

            ls -lh frontend-${BUILD_NUMBER}.zip

            echo "===== DOCKER BUILD DEBUG ====="
            pwd
            ls -la

            echo "===== localhelp.conf ====="
            cat localhelp.conf

            echo "===== Dockerfile ====="
            cat Dockerfile

            echo "============================="
        '''
    }
}


            stage('Docker Build and Push to ECR') {
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

                        echo "===== BUILDING FRONTEND DOCKER IMAGE ====="

                        docker build --no-cache \
    -t ${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${version} \
    .

                        echo "===== DOCKER IMAGE CREATED ====="

                        docker images | grep ${ECR_REPO}

                        echo "===== PUSHING IMAGE TO ECR ====="

                        docker push \
                            ${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${version}

                        echo "===== IMAGE PUSH COMPLETED ====="

                        echo "IMAGE:"
                        echo "${account_id}.dkr.ecr.${region}.amazonaws.com/${ECR_REPO}:${version}"
                    """
                }
            }
stage('Deploy to K8') {
    steps {
        withEnv(["IMAGE_VERSION=${version}"]) {
            sh '''
                set -e

                echo "========= GET BASTION PRIVATE IP =========="

                BASTION_IP=$(aws ec2 describe-instances \
                    --filters \
                      "Name=tag:Name,Values=localhelp-dev-bastion" \
                      "Name=instance-state-name,Values=running" \
                    --query 'Reservations[0].Instances[0].PrivateIpAddress' \
                    --output text)

                echo "Bastion IP: $BASTION_IP"

                if [ -z "$BASTION_IP" ] || [ "$BASTION_IP" = "None" ]; then
                    echo "ERROR: Bastion instance not found"
                    exit 1
                fi

                SSH_OPTS="-i /home/ec2-user/.ssh/jenkins_bastion -o StrictHostKeyChecking=no"

                echo "========= COPY HELM CHART TO BASTION =========="

                ssh $SSH_OPTS \
                    ec2-user@$BASTION_IP \
                    'rm -rf /tmp/frontend-helm'

                scp $SSH_OPTS \
                    -r helm \
                    ec2-user@$BASTION_IP:/tmp/frontend-helm

                echo "========= DEPLOY FRONTEND TO EKS =========="

                ssh $SSH_OPTS \
                    ec2-user@$BASTION_IP \
                    "IMAGE_VERSION='$IMAGE_VERSION' bash -s" <<'REMOTE_SCRIPT'

                    set -e

                    echo "========= CHECK KUBERNETES NODES =========="

                    kubectl get nodes

                    echo "========= GET FRONTEND TARGET GROUP ARN =========="

                    TARGET_GROUP_ARN=$(aws elbv2 describe-target-groups \
                        --region us-east-1 \
                        --names localhelp-dev-frontend \
                        --query 'TargetGroups[0].TargetGroupArn' \
                        --output text)

                    echo "TARGET GROUP ARN: $TARGET_GROUP_ARN"

                    if [ -z "$TARGET_GROUP_ARN" ] || [ "$TARGET_GROUP_ARN" = "None" ]; then
                        echo "ERROR: Frontend target group not found"
                        exit 1
                    fi

                    echo "========= DEPLOY FRONTEND USING HELM =========="

                    cd /tmp/frontend-helm

                    echo "========= CURRENT HELM VALUES =========="

                    cat values.yaml

                    echo "========= HELM UPGRADE / INSTALL =========="

                    helm upgrade --install frontend . \
                        --namespace localhelp \
                        --create-namespace \
                        --set deployment.imageVersion="$IMAGE_VERSION" \
                        --set targetGroup.arn="$TARGET_GROUP_ARN"

                    echo "========= HELM RELEASE STATUS =========="

                    helm status frontend \
                        --namespace localhelp

                    echo "========= CHECK FRONTEND DEPLOYMENT =========="

                    kubectl get deployment frontend \
                        -n localhelp

                    echo "========= CHECK FRONTEND PODS =========="

                    kubectl get pods \
                        -n localhelp \
                        -o wide

                    echo "========= WAIT FOR FRONTEND ROLLOUT =========="

                    kubectl rollout status \
                        deployment/frontend \
                        -n localhelp \
                        --timeout=5m

                    echo "========= FRONTEND ROLLOUT SUCCESSFUL =========="

                    kubectl get pods \
                        -n localhelp \
                        -l app=frontend \
                        -o wide

                    echo "========= FRONTEND SERVICE =========="

                    kubectl get svc frontend \
                        -n localhelp

                    echo "========= FRONTEND DEPLOYMENT COMPLETE =========="

REMOTE_SCRIPT
            '''
        }
    }
}

            stage('Upload Artifact to S3') {
                steps {
                    sh """
                        set -e

                        echo "===== UPLOADING FRONTEND ARTIFACT TO S3 ====="

                        aws s3 cp \
                            frontend-${version}.zip \
                            s3://localhelp-frontend-artifacts/frontend/${version}/frontend-${version}.zip

                        echo "===== S3 UPLOAD COMPLETED ====="

                        echo "===== VERIFYING S3 ARTIFACT ====="

                        aws s3 ls \
                            s3://localhelp-frontend-artifacts/frontend/${version}/

                        echo "===== FRONTEND ARTIFACT UPLOAD SUCCESSFUL ====="
                    """
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
                echo "Frontend Pipeline Successful"
                echo "Version: ${version}"
                echo "======================================"
            }

            failure {
                echo "======================================"
                echo "Frontend Pipeline Failed"
                echo "Version: ${version}"
                echo "======================================"
            }
        }
    }
}