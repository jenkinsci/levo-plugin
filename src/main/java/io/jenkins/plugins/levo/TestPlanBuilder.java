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
import java.util.List;
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

    /**
     * Fields that may appear multiple times in the configuration UI are stored as
     * lists. Jenkins/Stapler will bind an array of form values directly to a
     * {@code List} or array but will not bind it to a scalar type. Keeping
     * these fields as lists prevents WrongTypeException when duplicate form
     * elements are submitted. The getter methods return the first non-empty
     * element to maintain backwards compatibility with older versions of this
     * plugin that treated these values as scalars.
     */
    private java.util.List<String> target;
    private String testPlan;
    private String levoCredentialsId;
    private java.util.List<String> secretEnvironmentId;
    private java.util.List<Boolean> generateJunitReport;
    private java.util.List<String> extraCLIArgs;
    
    // Execution mode selection
    private String executionMode; // "testPlan", "appName", or "remoteTestRun"
    
    // New fields for app-based testing
    private java.util.List<String> appName;
    private java.util.List<String> environment;
    private java.util.List<String> categories;
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
    
    // Catch-all setter to ignore category checkboxes and other unknown fields
    // Category checkboxes update the categories text field via JavaScript,
    // so we don't need to process them on the server side
    // Note: Multiple checkboxes with the same name are sent as an array,
    // so we must accept List<Object> instead of Object
    @DataBoundSetter
    public void setCategoryCheckbox(List<Object> value) {
        // Ignore - category checkboxes are handled by JavaScript
    }
    
    // Ignore includeUser field if it exists in the form
    // Note: Multiple checkboxes/inputs with the same name are sent as an array,
    // so we must accept List<Object> instead of Object
    @DataBoundSetter
    public void setIncludeUser(List<Object> value) {
        // Ignore - not used in this builder
    }

    // Setter that handles List<Object>, String[], and String to work with Stapler's array handling
    @DataBoundSetter
    public void setTarget(List<Object> target) {
        if (target != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : target) {
                if (item == null) continue;
                String s = item.toString();
                // Keep even empty strings so that we know a value was provided
                values.add(s);
            }
            this.target = values;
        } else {
            this.target = null;
        }
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setTarget(String target) {
        if (target != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add(target);
            this.target = values;
        } else {
            this.target = null;
        }
    }
    
    @DataBoundSetter
    public void setTestPlan(String testPlan) {
        this.testPlan = testPlan;
    }
    
    @DataBoundSetter
    public void setLevoCredentialsId(String levoCredentialsId) {
        this.levoCredentialsId = levoCredentialsId;
    }
    
    // Setter that handles List<Object> (for arrays) and Boolean to work with Stapler's array handling
    @DataBoundSetter
    public void setGenerateJunitReport(List<Object> generateJunitReport) {
        if (generateJunitReport != null) {
            java.util.List<Boolean> values = new java.util.ArrayList<>();
            for (Object item : generateJunitReport) {
                if (item == null) {
                    values.add(null);
                    continue;
                }
                if (item instanceof Boolean) {
                    values.add((Boolean) item);
                } else {
                    // Try to parse as boolean
                    String str = item.toString().toLowerCase();
                    if ("true".equals(str) || "1".equals(str)) {
                        values.add(Boolean.TRUE);
                    } else if ("false".equals(str) || "0".equals(str) || str.isEmpty()) {
                        values.add(Boolean.FALSE);
                    } else {
                        // Unknown value, treat as null
                        values.add(null);
                    }
                }
            }
            this.generateJunitReport = values;
        } else {
            this.generateJunitReport = null;
        }
    }
    
    // Fallback for single Boolean values
    @DataBoundSetter
    public void setGenerateJunitReport(Boolean generateJunitReport) {
        if (generateJunitReport != null) {
            java.util.List<Boolean> values = new java.util.ArrayList<>();
            values.add(generateJunitReport);
            this.generateJunitReport = values;
        } else {
            this.generateJunitReport = null;
        }
    }
    
    // Setter that handles List<Object> to work with Stapler's array handling
    @DataBoundSetter
    public void setExtraCLIArgs(List<Object> extraCLIArgs) {
        if (extraCLIArgs != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : extraCLIArgs) {
                if (item == null) continue;
                values.add(item.toString());
            }
            this.extraCLIArgs = values;
        } else {
            this.extraCLIArgs = null;
        }
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setExtraCLIArgs(String extraCLIArgs) {
        if (extraCLIArgs != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add(extraCLIArgs);
            this.extraCLIArgs = values;
        } else {
            this.extraCLIArgs = null;
        }
    }
    
    // Setter that handles List<Object> to work with Stapler's array handling
    @DataBoundSetter
    public void setSecretEnvironmentId(List<Object> secretEnvironmentId) {
        if (secretEnvironmentId != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : secretEnvironmentId) {
                if (item == null) continue;
                values.add(item.toString());
            }
            this.secretEnvironmentId = values;
        } else {
            this.secretEnvironmentId = null;
        }
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setSecretEnvironmentId(String secretEnvironmentId) {
        if (secretEnvironmentId != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add(secretEnvironmentId);
            this.secretEnvironmentId = values;
        } else {
            this.secretEnvironmentId = null;
        }
    }
    
    @DataBoundSetter
    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }
    
    // Setter that handles List<Object>, String[], and String to work with Stapler's array handling
    @DataBoundSetter
    public void setAppName(List<Object> appName) {
        if (appName != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : appName) {
                if (item == null) continue;
                values.add(item.toString());
            }
            this.appName = values;
        } else {
            this.appName = null;
        }
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setAppName(String appName) {
        if (appName != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add(appName);
            this.appName = values;
        } else {
            this.appName = null;
        }
    }
    
    // Setter that handles List<Object>, String[], and String to work with Stapler's array handling
    @DataBoundSetter
    public void setEnvironment(List<Object> environment) {
        if (environment != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : environment) {
                if (item == null) continue;
                values.add(item.toString());
            }
            this.environment = values;
        } else {
            this.environment = null;
        }
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setEnvironment(String environment) {
        if (environment != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add(environment);
            this.environment = values;
        } else {
            this.environment = null;
        }
    }
    
    // Setter that handles List<Object> (for arrays), String[], and String to work with Stapler's array handling
    // Accepts List<Object> as primary type so Stapler can bind arrays correctly
    @DataBoundSetter
    public void setCategories(List<Object> categories) {
        if (categories != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            for (Object item : categories) {
                if (item == null) continue;
                values.add(item.toString());
            }
            this.categories = values;
        } else {
            this.categories = null;
        }
    }
    
    // Fallback setter for single String values (Stapler may call this for non-array values)
    @DataBoundSetter  
    public void setCategories(String categories) {
        if (categories != null) {
            java.util.List<String> values = new java.util.ArrayList<>();
            values.add(categories);
            this.categories = values;
        } else {
            this.categories = null;
        }
    }
    
    // Setter that handles List<Object> to work with Stapler's array handling
    @DataBoundSetter
    public void setHttpMethods(List<Object> httpMethods) {
        if (httpMethods != null) {
            for (Object item : httpMethods) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.httpMethods = item.toString().trim();
                    return;
                }
            }
        }
        this.httpMethods = null;
    }
    
    @DataBoundSetter
    public void setHttpMethods(String httpMethods) {
        this.httpMethods = httpMethods;
    }
    
    @DataBoundSetter
    public void setExcludeMethods(List<Object> excludeMethods) {
        if (excludeMethods != null) {
            for (Object item : excludeMethods) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.excludeMethods = item.toString().trim();
                    return;
                }
            }
        }
        this.excludeMethods = null;
    }
    
    @DataBoundSetter
    public void setExcludeMethods(String excludeMethods) {
        this.excludeMethods = excludeMethods;
    }
    
    @DataBoundSetter
    public void setEndpointPattern(List<Object> endpointPattern) {
        if (endpointPattern != null) {
            for (Object item : endpointPattern) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.endpointPattern = item.toString().trim();
                    return;
                }
            }
        }
        this.endpointPattern = null;
    }
    
    @DataBoundSetter
    public void setEndpointPattern(String endpointPattern) {
        this.endpointPattern = endpointPattern;
    }
    
    @DataBoundSetter
    public void setExcludeEndpointPattern(List<Object> excludeEndpointPattern) {
        if (excludeEndpointPattern != null) {
            for (Object item : excludeEndpointPattern) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.excludeEndpointPattern = item.toString().trim();
                    return;
                }
            }
        }
        this.excludeEndpointPattern = null;
    }
    
    @DataBoundSetter
    public void setExcludeEndpointPattern(String excludeEndpointPattern) {
        this.excludeEndpointPattern = excludeEndpointPattern;
    }
    
    // Setter that handles List<Object> to work with Stapler's array handling
    @DataBoundSetter
    public void setTestUsers(List<Object> testUsers) {
        if (testUsers != null) {
            for (Object item : testUsers) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.testUsers = item.toString().trim();
                    return; // Use first non-empty value
                }
            }
        }
        this.testUsers = null;
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setTestUsers(String testUsers) {
        this.testUsers = testUsers;
    }
    
    // Setter that handles List<Object> to work with Stapler's array handling
    @DataBoundSetter
    public void setTargetUrl(List<Object> targetUrl) {
        if (targetUrl != null) {
            for (Object item : targetUrl) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.targetUrl = item.toString().trim();
                    return; // Use first non-empty value
                }
            }
        }
        this.targetUrl = null;
    }
    
    // Fallback for single String values
    @DataBoundSetter
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }
    
    @DataBoundSetter
    public void setFailSeverity(List<Object> failSeverity) {
        if (failSeverity != null) {
            for (Object item : failSeverity) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.failSeverity = item.toString().trim();
                    return;
                }
            }
        }
        this.failSeverity = null;
    }
    
    @DataBoundSetter
    public void setFailSeverity(String failSeverity) {
        this.failSeverity = failSeverity;
    }
    
    @DataBoundSetter
    public void setFailScope(List<Object> failScope) {
        if (failScope != null) {
            for (Object item : failScope) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.failScope = item.toString().trim();
                    return;
                }
            }
        }
        this.failScope = null;
    }
    
    @DataBoundSetter
    public void setFailScope(String failScope) {
        this.failScope = failScope;
    }
    
    @DataBoundSetter
    public void setFailThreshold(List<Object> failThreshold) {
        if (failThreshold != null) {
            for (Object item : failThreshold) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.failThreshold = item.toString().trim();
                    return;
                }
            }
        }
        this.failThreshold = null;
    }
    
    @DataBoundSetter
    public void setFailThreshold(String failThreshold) {
        this.failThreshold = failThreshold;
    }

    /**
     * Returns the first non-empty value from the {@code target} list. If the list
     * is null or contains only empty entries, null is returned. This preserves
     * backwards compatibility with earlier versions of this plugin that stored
     * a single target as a scalar String.
     */
    public String getTarget() {
        if (target != null) {
            for (String s : target) {
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    public String getTestPlan() {
        return testPlan;
    }

    /**
     * Returns the first non-empty value from the {@code extraCLIArgs} list.
     */
    public String getExtraCLIArgs() {
        if (extraCLIArgs != null) {
            for (String s : extraCLIArgs) {
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first non-empty value from the {@code secretEnvironmentId} list.
     */
    public String getSecretEnvironmentId() {
        if (secretEnvironmentId != null) {
            for (String s : secretEnvironmentId) {
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    public String getLevoCredentialsId() {
        return levoCredentialsId;
    }

    /**
     * Returns the first non-null value from the {@code generateJunitReport} list.
     */
    /**
     * Returns the first non-null value from the {@code generateJunitReport} list.
     *
     * <p>
     * Jenkins form submissions sometimes post duplicate values when multiple form sections
     * define the same field. This method iterates over the collected values and returns
     * the first non-null Boolean. If no value has been set (i.e. the list is
     * {@code null} or contains only {@code null} entries), it returns {@code Boolean.FALSE}
     * instead of {@code null}. Returning a default value avoids SpotBugs warnings
     * (NP_BOOLEAN_RETURN_NULL) and makes the method safe for callers that expect a
     * non-null result.
     *
     * @return the configured value if present, otherwise {@code Boolean.FALSE}
     */
    public Boolean getGenerateJunitReport() {
        if (generateJunitReport != null) {
            for (Boolean b : generateJunitReport) {
                if (b != null) {
                    return b;
                }
            }
        }
        // Default to false when no value has been specified
        return Boolean.FALSE;
    }
    
    public String getExecutionMode() {
        return executionMode;
    }
    
    public String getAppName() {
        if (appName != null) {
            for (String s : appName) {
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }
    
    public String getEnvironment() {
        if (environment != null) {
            for (String s : environment) {
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }
    
    public String getCategories() {
        if (categories != null) {
            for (String s : categories) {
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return null;
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
    public void setDataSource(List<Object> dataSource) {
        if (dataSource != null) {
            for (Object item : dataSource) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.dataSource = item.toString().trim();
                    return;
                }
            }
        }
        this.dataSource = null;
    }
    
    @DataBoundSetter
    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
    
    public String getAppNameDataSource() {
        return appNameDataSource;
    }
    
    @DataBoundSetter
    public void setAppNameDataSource(List<Object> appNameDataSource) {
        if (appNameDataSource != null) {
            for (Object item : appNameDataSource) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.appNameDataSource = item.toString().trim();
                    return;
                }
            }
        }
        this.appNameDataSource = null;
    }
    
    @DataBoundSetter
    public void setAppNameDataSource(String appNameDataSource) {
        this.appNameDataSource = appNameDataSource;
    }
    
    public String getRunOn() {
        return runOn;
    }
    
    @DataBoundSetter
    public void setRunOn(List<Object> runOn) {
        if (runOn != null) {
            for (Object item : runOn) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    this.runOn = item.toString().trim();
                    return;
                }
            }
        }
        this.runOn = null;
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
        // Resolve secret environment ID from list
        String resolvedSecretEnvironmentId = getSecretEnvironmentId();
        if (resolvedSecretEnvironmentId != null && !resolvedSecretEnvironmentId.trim().isEmpty()) {
            StringCredentials secretCredentials = CredentialsProvider.findCredentialById(
                    resolvedSecretEnvironmentId,
                    StringCredentials.class,
                    run,
                    Lists.newArrayList()
            );
            if (secretCredentials == null) {
                FileCredentials secretFileCredentials = CredentialsProvider.findCredentialById(
                        resolvedSecretEnvironmentId,
                        FileCredentials.class,
                        run,
                        Lists.newArrayList()
                );
                if (secretFileCredentials == null) {
                    listener.error("Defined Secret Environment not found: " + resolvedSecretEnvironmentId);
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
        
        // Determine execution mode - default to appName
        String mode = executionMode != null && !executionMode.isEmpty() ? executionMode : "appName";
        
        if ("remoteTestRun".equals(mode)) {
            // Mode 3: Remote Test Run - uses levo remote-test-run command
            LevoDockerTool.runLevoRemoteTestRun(
                run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace),
                getAppName(), getEnvironment(), getCategories(), httpMethods, excludeMethods,
                endpointPattern, excludeEndpointPattern, testUsers, targetUrl,
                this.dataSource, this.runOn,
                failSeverity, failScope, failThreshold,
                credentials.getAuthorizationKey(), credentials.getOrganizationId(), credentials.getBaseUrl()
            );
        } else if ("appName".equals(mode)) {
            // Mode 2: Application Name (Local run) - uses levo test --app-name command
            // Note: testUsers is not passed since levo test --app-name doesn't support it yet
            LevoDockerTool.runLevoTestPlan(run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace), 
                getTarget(), null, getAppName(), getEnvironment(), getCategories(), this.appNameDataSource, null, environmentFileContent, 
                getGenerateJunitReport(), getExtraCLIArgs(), credentials.getOrganizationId(), credentials.getBaseUrl());
        } else {
            // Mode 1: Test Plan LRN - uses levo test --test-plan command
            LevoDockerTool.runLevoTestPlan(run, launcher, env, run.getEnvironment(listener), getPath(launcher, workspace), 
                getTarget(), testPlan, null, null, null, null, null, environmentFileContent, 
                getGenerateJunitReport(), getExtraCLIArgs(), credentials.getOrganizationId(), credentials.getBaseUrl());
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
                // Available for all project types
                return true;
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

