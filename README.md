# [Levo AI](https://levo.ai) 
Fully Automated API Security Testing in CI/CD

[![Levo AI](logo.png "Optional Title")](https://levo.ai)

Test your APIs using [Levo AI](https://levo.ai) test plans or app-based testing and get the results in real time.

## Usage

### Pre-requisites

In order to run this plugin you will need to:

- Have a [Levo AI](https://levo.ai) account
- Have a Levo CLI [Authorization Key](https://app.levo.ai/user/settings/keys)
- For test plan mode: Have a [Levo AI](https://app.levo.ai) test plan
- For app-based mode: Have an application configured in Levo AI

### Installation
- Go to "Manage Jenkins" > "Manage Plugins" > "Available".
- Search for "Levo".
- Install the plugin.

### Configuring Levo Test Step on your builds

After adding a Levo Test Plan step into your build, you'll need to configure some values.

#### Execution Modes

The plugin supports two execution modes:

1. **Test Plan LRN** : Run tests using a pre-configured test plan
2. **Application Name** : Dynamically create and run tests based on application configuration

#### Credentials

For credentials this plugin is relying on [credentials-plugin](https://plugins.jenkins.io/credentials/). You'll need to add a credential for the Levo API key.

- Click on the "Add" button next to the credentials dropdown.
- Select your datastore.
- Select "Levo Credential" as credential type.
- Enter your CLI Authorization Key.
- Enter your organization id that you can get from the [Organizations Tab](https://app.levo.ai/user/settings/organizations) in your user settings.
- Save and select the new credential.

#### Mode 1: Test Plan LRN

This mode uses a pre-configured test plan from your Levo organization.

**Configuration:**
- **Test Plan LRN**: The test plan identifier. Go to the Test Plan section of your Levo organization and click the "Copy LRN" button on the selected test plan.
- **Target**: The target URL to test
- **Extra CLI Arguments**: Additional command-line arguments (optional)
- **Generate JUnit Reports**: Check to generate JUnit XML reports

#### Mode 2: Application Name (App-Based Testing)

This mode automatically creates and runs tests based on your application configuration. No need to manually create test plans!

**Required Configuration:**
- **Application Name**: The name of your application in Levo AI
- **Environment**: The environment to test (e.g., "production", "staging")

**Optional Filtering:**
- **Categories**: Comma-separated test categories (default: BOLA,BFLA,INJECTION,SSRF)
- **HTTP Methods**: Comma-separated methods to include (e.g., GET,POST,PUT)
- **Exclude Methods**: Comma-separated methods to exclude (e.g., DELETE,OPTIONS)
- **Endpoint Pattern**: Regex pattern to include specific endpoints (e.g., `.*api.*`)
- **Exclude Endpoint Pattern**: Regex pattern to exclude endpoints
- **Test Users**: Comma-separated IAM test users
- **Target URL**: Override the app's default target URL

**Failure Criteria (Advanced):**
- **Fail Severity**: Minimum severity to fail the build (none, low, medium, high, critical)
- **Fail Scope**: Fail on new vulnerabilities, any vulnerabilities, or none
- **Fail Threshold**: Fail if vulnerability count exceeds this number

**Example Configuration:**
```
Application Name: my-api-app
Environment: production
Categories: BOLA,BFLA
HTTP Methods: GET,POST,PUT
Endpoint Pattern: .*api/v1.*
Fail Severity: high
Fail Scope: new
```

#### Environment File (Optional)

If you are using an [environment file](https://docs.levo.ai/test-your-app/test-app-security/data-driven/configure-env-yml) to define authentication details, you can add those details as a secret using a secret file in the credentials-plugin.

- Click on "Environment Secret Text" dropdown.
- Click on "Add" next to the credentials dropdown.
- Select your datastore.
- Select "Secret File" as credential type.
- Import the file.
- Save and select the new file.

#### You're ready to go!

## LICENSE

Licensed under Apache 2.0, see [LICENSE](LICENSE.md)

