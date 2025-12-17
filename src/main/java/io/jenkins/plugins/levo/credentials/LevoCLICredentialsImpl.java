/*
 * Copyright 2022 Levo Inc
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.jenkins.plugins.levo.credentials;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

public class LevoCLICredentialsImpl extends BaseStandardCredentials implements LevoCLICredentials {

    private final String organizationId;
    private final Secret authorizationKey;
    private final String baseUrl;

    @DataBoundConstructor
    public LevoCLICredentialsImpl(CredentialsScope scope, String id, String description, String organizationId, Secret authorizationKey, String baseUrl) {
        super(scope, id, description);
        this.organizationId = organizationId;
        this.authorizationKey = authorizationKey;
        // Default to production environment if not provided
        this.baseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : "https://api.levo.ai";
    }

    @Override
    public String getOrganizationId() {
        return this.organizationId;
    }

    @Override
    public Secret getAuthorizationKey() {
        return this.authorizationKey;
    }

    @Override
    public String getBaseUrl() {
        return this.baseUrl;
    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "Levo CLI Credentials";
        }
    }
}
