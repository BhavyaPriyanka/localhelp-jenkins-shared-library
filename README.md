## Backend-CI pipeline

# Jenkins Setup — Manual Steps After the Website Starts

This document covers the manual configuration required after the Jenkins web interface becomes available for the Localhelp CI/CD project.

> **Important:** Jenkins master, Jenkins agent, SonarQube, and the EKS bastion are separate roles. Do not connect Jenkins infrastructure to the EKS bastion.

## 1. Jenkins Initial Login

1. Open your Jenkins URL, for example:
   `http://jenkins-master.localhelp.store`
2. If the initial setup screen appears, run this on the Jenkins master:

   ```bash
   sudo cat /var/lib/jenkins/secrets/initialAdminPassword
   ```

3. Complete the setup wizard and create the Jenkins administrator account.
4. Install the suggested plugins and sign in.

If Jenkins is already initialized, sign in using your existing administrator account.

## 2. Install Required Plugins

Go to **Manage Jenkins → Plugins** (sometimes called Manage Plugins).

Verify that the required plugins are installed:

- Pipeline
- Git
- Credentials Binding
- SSH Build Agents
- SonarQube Scanner for Jenkins
- Docker Pipeline, if required by your pipeline
- AWS-related plugins only if your pipeline uses them

Install missing plugins and restart Jenkins if requested.

## 3. Configure the Jenkins Agent

Go to **Manage Jenkins → Nodes** and configure the agent with:

- Hostname: `jenkins-agent.localhelp.store`
- Correct SSH username and private-key credentials
- Remote root directory
- Label: `AGENT-1`

### Verify SSH Connectivity

Run these commands on the Jenkins master:

```bash
getent hosts jenkins-agent.localhelp.store
nc -vz jenkins-agent.localhelp.store 22
sudo -u jenkins ssh-keyscan -T 5 jenkins-agent.localhelp.store
```

Configure an appropriate host-key verification strategy. Prefer verifying the agent's host key instead of permanently disabling verification.

If SSH fails, check the agent's SSH service, security groups, DNS, username, and key.

Restrict SSH port 22 to the appropriate Jenkins master source.

### Install the MySQL Client on the Agent

The `Initialize RDS Database` stage calls the `mysql` command. Install the client on the machine that actually runs the pipeline shell steps.

For Amazon Linux 2023:

```bash
sudo dnf install -y mariadb105
which mysql
mysql --version
```

If the package is unavailable:

```bash
dnf search mariadb
```

Do not install this only on the Jenkins master if builds run on the agent.

## 4. Configure the SonarQube Server

Go to:

**Manage Jenkins → System → SonarQube installations**

Add or verify the following configuration:

- **Name:** `SonarQube`
- **Server URL:** `http://sonar.localhelp.store`
- **Authentication token:** Select the appropriate Jenkins token credential if required by the configuration.

Use your actual configured SonarQube URL.

The name must match the pipeline:

```groovy
withSonarQubeEnv('SonarQube') {
    // SonarQube analysis
}
```

## 5. Create the SonarQube Token Credential

### Generate the token

1. Sign in to SonarQube.
2. Open **My Account → Security**.
3. Generate a user token for Jenkins analysis.
4. Copy the token securely.

### Save the token in Jenkins

Go to:

**Manage Jenkins → Credentials → System → Global credentials → Add Credentials**

Enter:

| Field | Value |
|---|---|
| Kind | Secret text |
| Secret | Generated SonarQube token |
| ID | `sonarqube-token` |
| Description | SonarQube Analysis Token |

Click **Create**.

The credential ID must match the pipeline's `credentialsId: 'sonarqube-token'`.

Do not use the SonarQube administrator password as the token.

## 6. Configure the SonarScanner Tool Only If Needed

A pipeline using Maven's Sonar plugin directly normally does not need a separate SonarScanner tool installation.

If a pipeline invokes `sonar-scanner` or Jenkins' `tool` step:

1. Go to **Manage Jenkins → Tools**.
2. Find **SonarQube Scanner**.
3. Click **Add SonarQube Scanner**.
4. Set **Name:** `SonarScanner`.
5. Select **Install automatically**.
6. Choose an available version and save.

The name must match the pipeline's tool reference, if present:

```groovy
def scannerHome = tool 'SonarScanner'
```

Do not add this line unless the pipeline requires it.

## 7. Resolve the RDS Credential Binding

The pipeline retrieves the RDS endpoint and master secret from AWS Secrets Manager, but it previously failed because Jenkins could not find credential ID `localhelp-rds-admin`.

Search your shared-library repository:

```bash
cd ~/repos/localhelp-jenkins-shared-library

grep -Rni "localhelp-rds-admin" .
```

Choose the fix based on the code:

- If the pipeline genuinely requires a Jenkins username/password credential, create one with ID `localhelp-rds-admin`, using the intended RDS username and password from the approved secret source.
- If the stage already retrieves the credentials dynamically from Secrets Manager, fix the redundant or incorrect `withCredentials` binding instead of duplicating the database secret.

Never print secret values in Jenkins logs or commit them to Git.

## 8. Verify AWS Access on the Agent

The pipeline uses AWS CLI commands to describe RDS and retrieve its secret.

Run these commands on the agent:

```bash
aws --version
aws sts get-caller-identity
```

Prefer an EC2 instance profile/IAM role over long-lived AWS access keys.

Grant least-privilege permissions for the resources used by the pipeline, including:

- Describe the required RDS instance
- Retrieve the required Secrets Manager secret
- Push images to ECR
- Publish artifacts to S3

Only grant permissions needed by the actual pipeline.

## 9. Verify Required Build Tools

Run the following commands on the agent to verify tools used by the pipeline:

```bash
git --version
java -version
mvn -version
docker --version
aws --version
mysql --version
```

Install only the tools required by your pipeline.

If Docker commands run on the agent, confirm that Docker is running and the execution user has the necessary permissions.

## 10. Run a Validation Build

1. Save the Jenkins configuration.
2. Confirm the agent is online and has label `AGENT-1`.
3. Run the `backend-ci` job.
4. Read the console output.
5. Fix the first failing stage before investigating later stages, because Jenkins skips downstream stages after an earlier failure.

Expected pipeline stages include:

1. Load DB Credentials
2. Initialize RDS Database
3. Install Dependencies
4. Compile and Unit Tests
5. SonarQube Analysis
6. SonarQube Quality Gate
7. Build Docker Image
8. Push Image to ECR
9. Publish Build Artifacts to S3
10. Trigger CD Pipeline

The exact stages depend on your pipeline definition.

## Quick Reference

| Setting | Value |
|---|---|
| Jenkins agent label | `AGENT-1` |
| SonarQube server name | `SonarQube` |
| SonarQube token credential ID | `sonarqube-token` |
| Optional SonarScanner tool name | `SonarScanner` |
| RDS credential ID referenced by pipeline | `localhelp-rds-admin` — verify whether it is needed |
| MySQL client on Amazon Linux 2023 | `sudo dnf install -y mariadb105` |
| Jenkins agent SSH port | `22`, restricted to the appropriate source |
| AWS region | `us-east-1` |

## Security Reminders

- Never commit passwords, SonarQube tokens, AWS keys, or database secrets to Git.
- Do not print secret values in Jenkins logs.
- Prefer IAM roles and least-privilege permissions.
- Use appropriate network controls and HTTPS where configured.
- Keep Jenkins master infrastructure independent of the EKS bastion.
