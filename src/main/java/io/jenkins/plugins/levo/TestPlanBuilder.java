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

package io.jenkins.plugins.levo;

import io.jenkins.plugins.levo.credentials.LevoCLICredentials;
import io.jenkins.plugins.levo.helpers.LevoDockerTool;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class TestPlanBuilder extends Builder implements SimpleBuildStep {

    private final String target;
    private final String testPlan;
    private final String levoCredentialsId;
    private String secretEnvironmentId;

    @DataBoundConstructor
    public TestPlanBuilder(String target, String testPlan, String levoCredentialsId) {
        this.target = target;
        this.testPlan = testPlan;
        this.levoCredentialsId = levoCredentialsId;
    }

    @DataBoundSetter
    public void setSecretEnvironmentId(String secretEnvironmentId) {
        this.secretEnvironmentId = secretEnvironmentId;
    }

    public String getTarget() {
        return target;
    }

    public String getTestPlan() {
        return testPlan;
    }

    public String getSecretEnvironmentId() {
        return secretEnvironmentId;
    }

    public String getLevoCredentialsId() {
        return levoCredentialsId;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull EnvVars env, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        LevoCLICredentials credentials = CredentialsProvider.findCredentialById(
                levoCredentialsId,
                LevoCLICredentials.class,
                run,
                Lists.newArrayList()
        );
        if (credentials == null) {
            listener.error("Levo Credentials not found");
            return;
        }
        String environment = null;
        if (secretEnvironmentId != null) {
            StringCredentials secretCredentials = CredentialsProvider.findCredentialById(
                    secretEnvironmentId,
                    StringCredentials.class,
                    run,
                    Lists.newArrayList()
            );
            if (secretCredentials == null) {
                FileCredentials secretFileCredentials = CredentialsProvider.findCredentialById(
                        secretEnvironmentId,
                        FileCredentials.class,
                        run,
                        Lists.newArrayList()
                );
                if (secretFileCredentials == null) {
                    listener.error("Defined Secret Environment not found");
                    return;
                } else {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(secretFileCredentials.getContent(), StandardCharsets.UTF_8))){
                        environment = reader.lines().collect(Collectors.joining("\n"));
                    }
                }
            } else {
                environment = secretCredentials.getSecret().getPlainText();
            }
        }
        LevoDockerTool.runLevoLogin(run, launcher, env, getPath(launcher, workspace),credentials.getAuthorizationKey(), credentials.getOrganizationId());
        LevoDockerTool.runLevoTestPlan(run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace), target, testPlan, environment);
    }

    private String getPath(Launcher launcher, FilePath filePath) throws IOException, InterruptedException {
        return launcher.isUnix() ? filePath.getRemote() : filePath.toURI().getPath().substring(1).replace("\\", "/");
    }

    @Symbol("levo-test-plan")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

            public DescriptorImpl() {
                super(TestPlanBuilder.class);
            }

            @Override
            public String getDisplayName() {
                return "Levo Test Plan";
            }

            @Override
            public boolean isApplicable(Class<? extends AbstractProject> jobType) {
                Jenkins jenkins = Jenkins.getInstanceOrNull();
                if (jenkins == null) {
                    return false;
                }
                Plugin credentialsPlugin = jenkins.getPlugin("credentials");
                if (credentialsPlugin == null) {
                    return false;
                }
                return credentialsPlugin.getWrapper().isEnabled();
            }

            public ListBoxModel doFillLevoCredentialsIdItems(
                    @AncestorInPath Item item,
                    @QueryParameter String levoCredentialsId
            ) {
                StandardListBoxModel result = new StandardListBoxModel();

                if (item == null) {
                    if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                        return result.includeCurrentValue(levoCredentialsId); // (2)
                    }
                } else {
                    if (!item.hasPermission(Item.EXTENDED_READ)
                            && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                        return result.includeCurrentValue(levoCredentialsId); // (2)
                    }
                }
                return CredentialsProvider.listCredentials(
                        LevoCLICredentials.class,
                        (Item)null,
                        null,
                        Lists.newArrayList(),
                        CredentialsMatchers.instanceOf(LevoCLICredentials.class));
            }

            public ListBoxModel doFillSecretEnvironmentIdItems(
                    @AncestorInPath Item item,
                    @QueryParameter String secretEnvironmentId
            ) {
                StandardListBoxModel result = new StandardListBoxModel();

                if (item == null) {
                    if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                        return result.includeCurrentValue(secretEnvironmentId); // (2)
                    }
                } else {
                    if (!item.hasPermission(Item.EXTENDED_READ)
                            && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                        return result.includeCurrentValue(secretEnvironmentId); // (2)
                    }
                }
                return CredentialsProvider.listCredentials(
                        StandardCredentials.class,
                        (Item)null,
                        null,
                        Lists.newArrayList(),
                        CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(FileCredentials.class), CredentialsMatchers.instanceOf(StringCredentials.class)));
            }
    }
}
