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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.google.common.collect.Lists;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Plugin;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.levo.credentials.LevoCLICredentials;
import io.jenkins.plugins.levo.helpers.LevoDockerTool;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;

public class TestPlanBuilder extends Builder implements SimpleBuildStep {

    private String target;
    private String testPlan;
    private String levoCredentialsId;
    private String secretEnvironmentId;
    private Boolean generateJunitReport;
    private String extraCLIArgs;
    
    // Execution mode selection
    private String executionMode; // "testPlan", "appName", or "remoteTestRun"
    
    // New fields for app-based testing
    private String appName;
    private String environment;
    private String categories;
    private String httpMethods;
    private String excludeMethods;
    private String endpointPattern;
    private String excludeEndpointPattern;
    private String testUsers;
    private String targetUrl;
    
    // New required fields for remote-test-run
    private String dataSource; // "Test User Data" or "Traces" (user-facing)
    private String runOn;    // "cloud" or "on-prem"
    
    // Data source for appName mode (local run)
    private String appNameDataSource; // "Test User Data" or "Traces" (user-facing, optional, default: "Test User Data")
    
    // Failure criteria
    private String failSeverity; // "none", "low", "medium", "high", "critical"
    private String failScope; // "none", "new", "any"
    private String failThreshold;

    @DataBoundConstructor
    public TestPlanBuilder() {
        // Empty constructor - all fields set via @DataBoundSetter for flexibility
    }

    @DataBoundSetter
    public void setTarget(String target) {
        this.target = target;
    }
    
    @DataBoundSetter
    public void setTestPlan(String testPlan) {
        this.testPlan = testPlan;
    }
    
    @DataBoundSetter
    public void setLevoCredentialsId(String levoCredentialsId) {
        this.levoCredentialsId = levoCredentialsId;
    }
    
    @DataBoundSetter
    public void setGenerateJunitReport(Boolean generateJunitReport) {
        this.generateJunitReport = generateJunitReport;
    }
    
    @DataBoundSetter
    public void setExtraCLIArgs(String extraCLIArgs) {
        this.extraCLIArgs = extraCLIArgs;
    }
    
    @DataBoundSetter
    public void setSecretEnvironmentId(String secretEnvironmentId) {
        this.secretEnvironmentId = secretEnvironmentId;
    }
    
    @DataBoundSetter
    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }
    
    @DataBoundSetter
    public void setAppName(String appName) {
        this.appName = appName;
    }
    
    @DataBoundSetter
    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    @DataBoundSetter
    public void setCategories(String categories) {
        this.categories = categories;
    }
    
    @DataBoundSetter
    public void setHttpMethods(String httpMethods) {
        this.httpMethods = httpMethods;
    }
    
    @DataBoundSetter
    public void setExcludeMethods(String excludeMethods) {
        this.excludeMethods = excludeMethods;
    }
    
    @DataBoundSetter
    public void setEndpointPattern(String endpointPattern) {
        this.endpointPattern = endpointPattern;
    }
    
    @DataBoundSetter
    public void setExcludeEndpointPattern(String excludeEndpointPattern) {
        this.excludeEndpointPattern = excludeEndpointPattern;
    }
    
    @DataBoundSetter
    public void setTestUsers(String testUsers) {
        this.testUsers = testUsers;
    }
    
    @DataBoundSetter
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }
    
    @DataBoundSetter
    public void setFailSeverity(String failSeverity) {
        this.failSeverity = failSeverity;
    }
    
    @DataBoundSetter
    public void setFailScope(String failScope) {
        this.failScope = failScope;
    }
    
    @DataBoundSetter
    public void setFailThreshold(String failThreshold) {
        this.failThreshold = failThreshold;
    }

    public String getTarget() {
        return target;
    }

    public String getTestPlan() {
        return testPlan;
    }

    public String getExtraCLIArgs() {
        return extraCLIArgs;
    }

    public String getSecretEnvironmentId() {
        return secretEnvironmentId;
    }

    public String getLevoCredentialsId() {
        return levoCredentialsId;
    }

    public Boolean getGenerateJunitReport() {
        return generateJunitReport;
    }
    
    public String getExecutionMode() {
        return executionMode;
    }
    
    public String getAppName() {
        return appName;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public String getCategories() {
        return categories;
    }
    
    public String getHttpMethods() {
        return httpMethods;
    }
    
    public String getExcludeMethods() {
        return excludeMethods;
    }
    
    public String getEndpointPattern() {
        return endpointPattern;
    }
    
    public String getExcludeEndpointPattern() {
        return excludeEndpointPattern;
    }
    
    public String getTestUsers() {
        return testUsers;
    }
    
    public String getTargetUrl() {
        return targetUrl;
    }
    
    public String getDataSource() {
        return dataSource;
    }
    
    @DataBoundSetter
    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
    
    public String getAppNameDataSource() {
        return appNameDataSource;
    }
    
    @DataBoundSetter
    public void setAppNameDataSource(String appNameDataSource) {
        this.appNameDataSource = appNameDataSource;
    }
    
    public String getRunOn() {
        return runOn;
    }
    
    @DataBoundSetter
    public void setRunOn(String runOn) {
        this.runOn = runOn;
    }
    
    public String getFailSeverity() {
        return failSeverity;
    }
    
    public String getFailScope() {
        return failScope;
    }
    
    public String getFailThreshold() {
        return failThreshold;
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
        String environmentFileContent = null;
        if (secretEnvironmentId != null && !secretEnvironmentId.trim().isEmpty()) {
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
                    listener.error("Defined Secret Environment not found: " + secretEnvironmentId);
                    run.setResult(Result.FAILURE);
                    return;
                } else {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(secretFileCredentials.getContent(), StandardCharsets.UTF_8))){
                        environmentFileContent = reader.lines().collect(Collectors.joining("\n"));
                    }
                }
            } else {
                environmentFileContent = secretCredentials.getSecret().getPlainText();
            }
        }
        // Login is required for authentication
        LevoDockerTool.runLevoLogin(run, launcher, env, getPath(launcher, workspace), credentials.getAuthorizationKey(), credentials.getOrganizationId(), credentials.getBaseUrl());
        
        // Determine execution mode - default to testPlan for backward compatibility
        String mode = executionMode != null && !executionMode.isEmpty() ? executionMode : "testPlan";
        
        if ("remoteTestRun".equals(mode)) {
            // Mode 3: Remote Test Run - uses levo remote-test-run command
            LevoDockerTool.runLevoRemoteTestRun(
                run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace),
                appName, this.environment, categories, httpMethods, excludeMethods,
                endpointPattern, excludeEndpointPattern, testUsers, targetUrl,
                this.dataSource, this.runOn,
                failSeverity, failScope, failThreshold,
                credentials.getAuthorizationKey(), credentials.getOrganizationId(), credentials.getBaseUrl()
            );
        } else if ("appName".equals(mode)) {
            // Mode 2: Application Name (Local run) - uses levo test --app-name command
            LevoDockerTool.runLevoTestPlan(run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace), 
                target, null, appName, this.environment, categories, this.appNameDataSource, environmentFileContent, 
                this.generateJunitReport, this.extraCLIArgs, credentials.getOrganizationId(), credentials.getBaseUrl());
        } else {
            // Mode 1: Test Plan LRN - uses levo test --test-plan command
            LevoDockerTool.runLevoTestPlan(run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace), 
                target, testPlan, null, null, null, null, environmentFileContent, 
                this.generateJunitReport, this.extraCLIArgs, credentials.getOrganizationId(), credentials.getBaseUrl());
        }
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

            @RequirePOST
            public FormValidation doCheckAppName(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String executionMode) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                if (("appName".equals(executionMode) || "remoteTestRun".equals(executionMode)) && (value == null || value.trim().isEmpty())) {
                    return FormValidation.error("Application name is required when using app-based testing");
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckEnvironment(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String executionMode) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                if (("appName".equals(executionMode) || "remoteTestRun".equals(executionMode)) && (value == null || value.trim().isEmpty())) {
                    return FormValidation.error("Environment is required when using app-based testing");
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckTarget(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String executionMode) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                // Target URL is required for testPlan mode, optional for appName mode (will use default from app config)
                if (("testPlan".equals(executionMode) || executionMode == null || executionMode.isEmpty()) && (value == null || value.trim().isEmpty())) {
                    return FormValidation.error("Target URL is required when using Test Plan LRN");
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckTargetUrl(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String executionMode) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                if ("remoteTestRun".equals(executionMode) && (value == null || value.trim().isEmpty())) {
                    return FormValidation.error("Target URL is required for remote test run");
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckEndpointPattern(@AncestorInPath Item item, @QueryParameter String value) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                if (value != null && !value.trim().isEmpty()) {
                    try {
                        Pattern.compile(value);
                        return FormValidation.ok();
                    } catch (PatternSyntaxException e) {
                        return FormValidation.error("Invalid regex pattern: " + e.getMessage());
                    }
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckExcludeEndpointPattern(@AncestorInPath Item item, @QueryParameter String value) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                if (value != null && !value.trim().isEmpty()) {
                    try {
                        Pattern.compile(value);
                        return FormValidation.ok();
                    } catch (PatternSyntaxException e) {
                        return FormValidation.error("Invalid regex pattern: " + e.getMessage());
                    }
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckTestUsers(@AncestorInPath Item item, @QueryParameter String value) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok(); // Return OK if no permission to avoid exposing validation logic
                }
                if (value != null && !value.trim().isEmpty()) {
                    String[] userArray = value.split(",");
                    for (String user : userArray) {
                        String trimmedUser = user.trim();
                        if (trimmedUser.isEmpty()) {
                            return FormValidation.warning("Empty test user name found. Please remove empty entries or use comma-separated format: User1,User2");
                        }
                    }
                }
                return FormValidation.ok();
            }

            public ListBoxModel doFillDataSourceItems() {
                ListBoxModel items = new ListBoxModel();
                items.add("Test User Data", "Test User Data");
                items.add("Traces", "Traces");
                return items;
            }
            
            public ListBoxModel doFillAppNameDataSourceItems() {
                ListBoxModel items = new ListBoxModel();
                items.add("Test User Data", "Test User Data");
                items.add("Traces", "Traces");
                return items;
            }

            public ListBoxModel doFillRunOnItems() {
                ListBoxModel items = new ListBoxModel();
                items.add("Cloud", "cloud");
                items.add("On-Premises", "on-prem");
                return items;
            }

            @RequirePOST
            public FormValidation doCheckDataSource(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String executionMode) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok();
                }
                if ("remoteTestRun".equals(executionMode) && (value == null || value.trim().isEmpty())) {
                    return FormValidation.error("Data source is required for remote test run");
                }
                if (value != null && !value.trim().isEmpty()) {
                    String normalized = value.trim();
                    if (!"Test User Data".equalsIgnoreCase(normalized) && !"Traces".equalsIgnoreCase(normalized)) {
                        return FormValidation.error("Data source must be either 'Test User Data' or 'Traces'");
                    }
                }
                return FormValidation.ok();
            }

            @RequirePOST
            public FormValidation doCheckRunOn(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String executionMode) {
                if (item != null && !item.hasPermission(Item.CONFIGURE)) {
                    return FormValidation.ok();
                }
                if ("remoteTestRun".equals(executionMode) && (value == null || value.trim().isEmpty())) {
                    return FormValidation.error("Run on is required for remote test run");
                }
                if (value != null && !value.trim().isEmpty()) {
                    String normalized = value.trim().toLowerCase();
                    if (!"cloud".equals(normalized) && !"on-prem".equals(normalized)) {
                        return FormValidation.error("Run on must be either 'cloud' or 'on-prem'");
                    }
                }
                return FormValidation.ok();
            }

            public ListBoxModel doFillFailSeverityItems() {
                ListBoxModel items = new ListBoxModel();
                items.add("None", "none");
                items.add("Low", "low");
                items.add("Medium", "medium");
                items.add("High", "high");
                items.add("Critical", "critical");
                return items;
            }

            public ListBoxModel doFillFailScopeItems() {
                ListBoxModel items = new ListBoxModel();
                items.add("None", "none");
                items.add("New vulnerabilities", "new");
                items.add("Any vulnerabilities", "any");
                return items;
            }
        }
}

