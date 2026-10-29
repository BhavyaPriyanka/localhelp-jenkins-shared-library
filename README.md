
# LocalHelp Jenkins Shared Library

## Overview

The LocalHelp Jenkins Shared Library provides reusable CI/CD pipelines for the LocalHelp frontend and backend applications.

It integrates Jenkins with GitHub, SonarQube, Docker, Amazon ECR, Amazon S3, Amazon RDS, AWS Secrets Manager, and the GitOps deployment workflow using Helm, Argo CD, and Amazon EKS.

The library supports:
- Frontend CI and CD
- Backend CI and CD
- Automated application builds and testing
- SonarQube static code analysis and Quality Gate validation
- Versioned Docker image creation and publishing to Amazon ECR
- Versioned build artifact storage in Amazon S3
- RDS endpoint and database credential discovery
- Database initialization
- Automated GitOps configuration updates
- Argo CD deployment handoff
- Pipeline failure handling and workspace cleanup

## Architecture

Developer Push
      |
      v
GitHub Application Repository
      |
      v
Jenkins Shared Library
      |
      +----------------------------+
      |                            |
      v                            v
Frontend CI                  Backend CI
      |                            |
      |                            +--> Discover RDS Endpoint
      |                            +--> Load Secrets Manager Credentials
      |                            +--> Initialize Database
      |                            +--> Maven Build and Tests
      |                            +--> SonarQube Analysis
      |                            +--> Quality Gate
      |                            +--> Docker Image
      |                            +--> ECR and S3
      |                            +--> Trigger Backend CD
      |
      +--> npm ci
      +--> SonarQube Analysis
      +--> Quality Gate
      +--> React Production Build
      +--> Docker Image
      +--> ECR and S3
      +--> Trigger Frontend CD
                                   |
                                   v
                           GitOps Repository
                                   |
                                   v
                                Argo CD
                                   |
                                   v
                              Amazon EKS
                                   |
                                   v
                         LocalHelp Application

## Repository Structure

The shared library exposes reusable pipeline entry points through the `vars/` directory.

```text
localhelp-jenkins-shared-library/
└── vars/
    ├── nodeJSEKS.groovy
    ├── nodeJSEKSCD.groovy
    ├── javaEKSCI.groovy
    ├── javaEKSCD.groovy
    └── pipelineDecision.groovy
```

| Pipeline | Responsibility |
|---|---|
| `nodeJSEKS` | Frontend continuous integration |
| `nodeJSEKSCD` | Frontend GitOps configuration update |
| `javaEKSCI` | Backend continuous integration |
| `javaEKSCD` | Backend GitOps configuration update |
| `pipelineDecision` | Pipeline selection and dispatching |

## 1. Frontend CI — `nodeJSEKS`

The frontend CI pipeline builds the React.js application, performs code-quality checks, packages the production build, publishes a Docker image to ECR, uploads the ZIP artifact to S3, and triggers frontend CD.

### Stage 1: Initialize Version

- Uses the Jenkins build number as `IMAGE_VERSION`.
- Loads the application name, AWS region, AWS account ID, and ECR repository from the pipeline configuration.

### Stage 2: Install Dependencies

- Enters the `frontend` directory.
- Validates `package.json` and `package-lock.json`.
- Runs `npm ci` for reproducible dependency installation.

### Stage 3: SonarQube Analysis

- Uses the configured SonarScanner installation.
- Connects to the SonarQube server through `withSonarQubeEnv`.
- Analyzes source code under `frontend/src`.
- Excludes dependency, build, distribution, and coverage directories.
- Uses `localhelp-frontend` as the SonarQube project key.
- Writes the scanner task report to `.scannerwork/report-task.txt`.

### Stage 4: SonarQube Quality Gate

- Reads the SonarQube task report.
- Polls the Compute Engine task until analysis completes or times out.
- Retrieves the analysis ID.
- Queries the SonarQube Quality Gate API.
- Fails the pipeline if analysis fails, times out, or the Quality Gate status is not `OK`.

### Stage 5: Build Frontend Application

- Runs `npm run build`.
- Accepts either `dist/` or `build/` as the generated production output.
- Fails if neither output directory exists.

### Stage 6: Build Frontend Image

- Validates the frontend Dockerfile.
- Builds the Docker image using the configured ECR repository and version tag.
- Inspects the resulting image.

### Stage 7: Package Frontend Artifact

- Selects `dist/` or `build/` as the production build directory.
- Creates a versioned ZIP artifact.
- Verifies that the ZIP file exists and is not empty.

Artifact naming:

`frontend-<IMAGE_VERSION>.zip`

### Stage 8: ECR Login

- Authenticates Docker with Amazon ECR using the AWS CLI.
- Uses the configured AWS region and account ID.

### Stage 9: Push Image to ECR

- Pushes the versioned frontend Docker image.
- Queries ECR to verify that the image tag exists.
- Retrieves and logs the image digest.

Image naming:

`<AWS_ACCOUNT_ID>.dkr.ecr.<AWS_REGION>.amazonaws.com/<ECR_REPO>:<IMAGE_VERSION>`

### Stage 10: Upload Artifact to S3

- Uploads the versioned ZIP artifact to Amazon S3.
- Lists the destination path to verify the uploaded artifact is present.

S3 path:

`s3://localhelp-frontend-artifacts/frontend/<IMAGE_VERSION>/frontend-<IMAGE_VERSION>.zip`

### Stage 11: Trigger Frontend CD

- Triggers the Jenkins job `frontend-CD`.
- Passes `IMAGE_VERSION` through the `IMAGE_TAG` parameter.
- Waits for the downstream job to complete.
- Propagates downstream failure to the frontend CI result.

### Stage 12: CI Result and Cleanup

- Reports the application, image, version, and build result.
- Deletes the Jenkins workspace in the `always` post action.

## 2. Frontend CD — `nodeJSEKSCD`

The frontend CD pipeline validates the release, discovers the AWS load balancer target group ARN, updates the frontend Helm values in the GitOps repository, and pushes the change.

### Stage 1: Validate Release

- Requires a non-empty `IMAGE_TAG`.
- Validates the image tag format.
- Prepares the image reference for the release.

### Stage 2: Discover Target Group ARN

- Queries Elastic Load Balancing using the configured target group name `localhelp-dev-frontend`.
- Uses AWS region `us-east-1`.
- Validates the returned target group ARN.

### Stage 3: Checkout GitOps Repository

- Checks out the configured GitOps branch.
- Uses the configured GitHub credentials.
- Validates the frontend Helm values file.
- Confirms that the expected frontend ECR repository is configured.
- Requires exactly one `imageVersion` entry and one `arn` entry.

Default values file:

`apps/frontend/helm/values.yaml`

### Stage 4: Update Frontend GitOps Values

- Uses Python to update the Helm values file.
- Updates `imageVersion` with the supplied image tag.
- Updates `arn` with the discovered target group ARN.
- Validates that the expected image repository, version, and ARN are present.

### Stage 5: Commit and Push GitOps Change

- Configures the Git commit identity.
- Skips the commit if the values file has no changes.
- Commits the updated values file.
- Authenticates the Git push using the configured Jenkins credential.
- Pushes the change to the configured GitOps branch.

### Post Actions

- Reports whether the GitOps handoff succeeded or failed.
- Deletes the Jenkins workspace.
- Leaves application deployment to Argo CD.

## 3. Backend CI — `javaEKSCI`

The backend CI pipeline builds and verifies the Java Spring Boot application, discovers RDS connection information, initializes the database, runs SonarQube checks, publishes the Docker image and build artifacts, and triggers backend CD.

### Stage 1: Checkout

- Checks out the application source.
- Uses an exact Git tag when available.
- Otherwise generates a version from the shortened Git commit and Jenkins build number.
- Normalizes the version for use as a Docker image tag.
- Validates `pom.xml` and `Dockerfile`.
- Checks Java, Maven, AWS CLI, and Docker availability.

Version format when no exact Git tag is available:

`sha-<SHORT_COMMIT>-<BUILD_NUMBER>`

### Stage 2: Load DB Credentials

- Discovers the RDS endpoint for `localhelp-dev`.
- Discovers the AWS-managed master-secret ARN.
- Retrieves the secret from AWS Secrets Manager.
- Loads the database username and password into pipeline environment variables.
- Avoids intentionally printing the retrieved secret values.

### Stage 3: Initialize RDS Database

- Uses the Jenkins credential `localhelp-rds-admin`.
- Connects to the discovered RDS endpoint on port 3306.
- Executes the database initialization script:

`backend/src/main/resources/db/init.sql`

### Stage 4: Install Dependencies

- Enters the configured backend directory.
- Validates the Maven project file.
- Runs `mvn -B dependency:resolve`.

### Stage 5: Compile and Unit Tests

- Runs `mvn -B clean verify`.
- Compiles the backend application.
- Executes the configured tests and Maven verification lifecycle.
- Stops the pipeline if verification fails.

### Stage 6: SonarQube Analysis

- Connects to the SonarQube installation named `SonarQube`.
- Runs the Sonar Maven plugin.
- Uses the configured project key, component name, and build version.
- Submits the backend source code for static analysis.

### Stage 7: SonarQube Quality Gate

- Reads the scanner report and extracts the Compute Engine task URL.
- Polls SonarQube until the background analysis succeeds, fails, is canceled, or times out.
- Retrieves the analysis ID.
- Queries the Quality Gate API.
- Fails the build unless the Quality Gate status is `OK`.

### Stage 8: Build Docker Image

- Builds the backend image from the configured application directory.
- Applies the build version and Git revision as image labels.
- Tags the image with `BUILD_VERSION`.

### Stage 9: Push Image to ECR

- Confirms the AWS caller identity.
- Authenticates Docker with Amazon ECR.
- Pushes the versioned backend image.
- Logs out of ECR after publishing.

### Stage 10: Publish Build Artifacts to S3

- Locates the packaged application JAR in the Maven `target` directory.
- Excludes source, Javadoc, and original JAR files from the selection.
- Uploads the selected JAR to Amazon S3.
- Uploads `db/init.sql` when the file exists.

Artifact path:

`s3://localhelp-backend-artifacts/backend/<BUILD_VERSION>/<application>.jar`

Database script path:

`s3://localhelp-backend-artifacts/backend/<BUILD_VERSION>/init.sql`

### Stage 11: Trigger CD Pipeline

- Triggers the configured Jenkins job `backend-CD`.
- Passes `BUILD_VERSION` through the `IMAGE_TAG` parameter.
- Does not wait for the downstream CD pipeline to finish.

### Post Actions

- Reports CI success or failure.
- Logs the image reference after a successful build.
- Prunes unused Docker images.
- Deletes the Jenkins workspace.

## 4. Backend CD — `javaEKSCD`

The backend CD pipeline updates the backend Helm values file in the GitOps repository with the image tag produced by CI.

### Stage 1: Validate Release

- Requires a non-empty `IMAGE_TAG`.
- Validates the tag format.
- Prepares the backend image reference.

### Stage 2: Checkout GitOps Repository

- Checks out the configured GitOps branch.
- Uses the configured GitHub credential.
- Validates the backend values file.
- Confirms that the expected ECR repository is present.
- Requires exactly one `imageVersion` entry.

Default values file:

`apps/backend/helm/values.yaml`

### Stage 3: Update Backend Image Version

- Updates `imageVersion` in the backend Helm values file.
- Confirms that the configured ECR repository remains unchanged.
- Verifies that the expected image tag was written.

### Stage 4: Commit and Push GitOps Change

- Configures the Git commit identity.
- Displays the values-file diff for troubleshooting.
- Skips creating a commit when the file already contains the requested image tag.
- Commits the changed values file.
- Authenticates and pushes the change to the configured GitOps branch.

### Post Actions

- Reports whether the GitOps handoff succeeded or failed.
- Deletes the Jenkins workspace.
- Leaves deployment reconciliation to Argo CD.

## 5. Amazon S3 Artifact Storage

Amazon S3 stores versioned application artifacts separately from the Docker images stored in ECR.

| Application | Artifact | Destination |
|---|---|---|
| Frontend | Production build ZIP | `s3://localhelp-frontend-artifacts/frontend/<version>/frontend-<version>.zip` |
| Backend | Packaged application JAR | `s3://localhelp-backend-artifacts/backend/<version>/<application>.jar` |
| Backend | Database initialization SQL, when present | `s3://localhelp-backend-artifacts/backend/<version>/init.sql` |

The versioned paths make artifacts easier to identify and retrieve for a particular build.

## 6. SonarQube Quality Controls

Both frontend and backend CI contain separate SonarQube analysis and Quality Gate stages.

The analysis stage submits the source code. The Quality Gate stage waits for analysis completion and checks the resulting status.

A failed or timed-out Quality Gate stops the corresponding CI pipeline before image publication and the downstream CD trigger.

## 7. GitOps Deployment Flow

The CD pipelines update deployment configuration in the GitOps repository instead of directly applying Kubernetes resources.

Frontend CD updates:
- `imageVersion`
- Target group `arn`

Backend CD updates:
- `imageVersion`

After the GitOps commit is pushed, Argo CD is responsible for detecting the desired-state change and reconciling it with the EKS cluster.

## 8. Integrations

| Technology | Purpose |
|---|---|
| Jenkins | CI/CD orchestration |
| GitHub | Application source and GitOps configuration |
| Node.js and npm | Frontend dependency installation and build |
| Java and Maven | Backend dependency resolution, compilation, and tests |
| SonarQube | Static code analysis and Quality Gate |
| Docker | Container image build |
| Amazon ECR | Container image storage |
| Amazon S3 | Versioned build artifact storage |
| Amazon RDS | MySQL database |
| AWS Secrets Manager | RDS master credentials |
| AWS IAM | Permissions for AWS operations |
| Helm | Kubernetes application packaging and values |
| Argo CD | GitOps reconciliation |
| Amazon EKS | Kubernetes application runtime |

## 9. Security and Reliability

- Store credentials in Jenkins Credentials or AWS Secrets Manager.
- Avoid logging database passwords, GitHub tokens, or SonarQube tokens.
- Use least-privilege IAM permissions for AWS operations.
- Use versioned Docker images and artifacts.
- Fail CI when Maven verification or SonarQube Quality Gate validation fails.
- Validate the target group ARN and Helm values before committing GitOps changes.
- Keep Jenkins controller infrastructure separate from the EKS bastion infrastructure.
- Review the database initialization script before running it against an existing database.
- Monitor Argo CD sync and health status after a GitOps update.

## 10. End-to-End Workflow

1. A developer pushes application code to GitHub.
2. Jenkins checks out the source and generates a version.
3. CI installs dependencies and builds the application.
4. SonarQube analyzes the source and validates the Quality Gate.
5. Jenkins builds and pushes a versioned Docker image to ECR.
6. CI uploads the versioned build artifact to S3.
7. CI triggers the corresponding CD Jenkins job with `IMAGE_TAG`.
8. CD updates the application Helm values in the GitOps repository.
9. CD commits and pushes the GitOps change.
10. Argo CD reconciles the desired configuration with Amazon EKS.

## Summary

The LocalHelp Jenkins Shared Library standardizes frontend and backend CI/CD operations.

It provides reproducible builds, automated code-quality validation, versioned Docker image publishing, S3 artifact storage, RDS database initialization, and GitOps-based deployment handoff to Argo CD.
