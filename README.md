# [Levo AI](https://levo.ai) 
Fully Automated API Security Testing in CI/CD

[![Levo AI](logo.png "Optional Title")](https://levo.ai)

Test your APIs using [Levo AI](https://levo.ai) test plans and get the results in real time.

## Usage

### Pre-requisites

In order to run this plugin you will need to:

- Have a [Levo AI](https://levo.ai) account
- Have a [Levo AI](https://app.levo.ai) test plan
- Have a Levo CLI [Authorization Key](https://app.levo.ai/user/settings/keys)


### Installation
- Go to "Manage Jenkins" > "Manage Plugins" > "Available".
- Search for "Levo".
- Install the plugin.

### Configuring Test Plan Step on your builds

After adding a Test Plan step into your build you'll need to confgiure some values.

#### Test Plan

In order to specify the test plan to run you'll need to go to the Test Plan section of your Levo organization and click the "Copy LRN" button on the selected test plan.

#### Credentials

For credentials this plugin is relying on [credentials-plugin](https://plugins.jenkins.io/credentials/). You'll need to add a credential for the Levo API key.

- Click on the "Add" button next to the credentials dropdown.
- Select you datastore.
- Select "Levo Credential" as credential type.
- Enter your CLI Authorization Key.
- Enter your organization id that you can get from the [Organizations Tab](https://app.levo.ai/user/settins/organizations) in your user settings.
- Save and select the new credential.


#### Environment

If you are using an [environment file](https://docs.levo.ai/test-your-app/test-app-security/data-driven/configure-env-yml) to define authentication details you add those details as a secret using a secret file in the credentials-plugin.

- Click on "Advanced".
- Click on "Add" next to the credentials dropdown.
- Select you datastore.
- Select "Secret File" as credential type.
- Import the file.
- Save and select the new file.

#### You're ready to go!

## LICENSE

Licensed under Apache 2.0, see [LICENSE](LICENSE.md)

