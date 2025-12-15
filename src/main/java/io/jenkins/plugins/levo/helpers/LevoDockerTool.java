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

package io.jenkins.plugins.levo.helpers;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.jenkinsci.plugins.docker.commons.tools.DockerTool;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;

public class LevoDockerTool {
    public static final int CLIENT_TIMEOUT = 1800;
    public static final int PULL_TIMEOUT = 300;
    public static final int CMD_TIMEOUT = 60;
    public static final String ENV_FILE_NAME = "environment.yaml";
    public static final String LEVO_CONFIG_FOLDER_NAME = ".levoconfig";
    public static final String LEVO_REPORTS_FOLDER_NAME = "levo-reports";


    private static String runAndParseOutput(Launcher launcher, EnvVars envVars, ArgumentListBuilder cmd) throws IOException, InterruptedException {
        return runAndParseOutput(launcher, envVars, cmd, CMD_TIMEOUT);
    }

    private static String runAndParseOutput(Launcher launcher, EnvVars envVars, ArgumentListBuilder cmd, int timeout) throws IOException, InterruptedException {
        Launcher.ProcStarter procStarter = launcher.launch();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        procStarter.quiet(false)
                .cmds(cmd)
                .envs(envVars)
                .stdout(baos)
                .start()
                .joinWithTimeout(timeout, TimeUnit.SECONDS, launcher.getListener());
        return baos.toString(StandardCharsets.UTF_8.name()).trim();
    }
    private static String getUserId(Launcher launcher, EnvVars launchEnv) throws IOException, InterruptedException {
        ArgumentListBuilder argb = new ArgumentListBuilder();
        argb.add("id", "-u");
        return runAndParseOutput(launcher, launchEnv, argb);
    }

    private static String getUserGroupId(Launcher launcher, EnvVars launchEnv) throws IOException, InterruptedException {
        ArgumentListBuilder argb = new ArgumentListBuilder();
        argb.add("id", "-g");
        return runAndParseOutput(launcher, launchEnv, argb);
    }

    private static ArgumentListBuilder buildLevoCommand(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir, @Nullable String baseUrl) throws IOException, InterruptedException {
        Node currentNode = Optional.of(run)
                .map(Run::getExecutor)
                .map(Executor::getOwner)
                .map(Computer::getNode)
                .orElse(null);
        if (currentNode == null) {
            throw new IllegalStateException("Run has no executor");
        }
        ArgumentListBuilder argb = new ArgumentListBuilder();
        argb.add(DockerTool.getExecutable(null, currentNode, launcher.getListener(), launchEnv), "run");

        Path levoConfigPath = Paths.get(workdir, LEVO_CONFIG_FOLDER_NAME);
        if (!Files.exists(levoConfigPath)) {
            Files.createDirectory(levoConfigPath);
        }

        Path levoReportsPath = Paths.get(workdir, LEVO_REPORTS_FOLDER_NAME);
        if (!Files.exists(levoReportsPath)) {
            Files.createDirectory(levoReportsPath);
        }
        argb.add("-v", levoConfigPath + ":/home/levo/.config/configstore:rw");
        argb.add("-v", levoReportsPath + ":/home/levo/reports:rw");
        argb.add("-v", workdir + ":/home/levo/work:rw");

        // If Jenkins agent is running on Linux, set the current user and group ids because Docker volume mounts
        // on Linux need these special settings.
        Computer computer = Optional.of(currentNode).map(Node::toComputer).orElse(null);
        Map<Object, Object> systemProperties = null;
        if (computer != null) {
            systemProperties = computer.getSystemProperties();
        }
        if (systemProperties != null)
        {
            Object osName = systemProperties.get("os.name");
            if (osName instanceof String && ((String) osName).toLowerCase().contains("linux")) {
                argb.add("-e", "LOCAL_USER_ID=" + getUserId(launcher, launchEnv));
                argb.add("-e", "LOCAL_GROUP_ID=" + getUserGroupId(launcher, launchEnv));
            }
        }

        argb.add("-e", "TERM=xterm-256color");
        
        // Set API base URL from credentials (defaults to production if not provided)
        String apiBaseUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : "https://api.levo.ai";
        argb.add("-e", "LEVO_BASE_URL=" + apiBaseUrl);

        argb.add("levoai/levo:stable");

        return argb;
    }

    private static void runDockerPull(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv) throws IOException, InterruptedException {
        Node currentNode = Optional.of(run)
                .map(Run::getExecutor)
                .map(Executor::getOwner)
                .map(Computer::getNode)
                .orElse(null);
        if (currentNode == null) {
            throw new IllegalStateException("Run has no executor");
        }
        ArgumentListBuilder argb = new ArgumentListBuilder();
        argb.add(DockerTool.getExecutable(null, currentNode, launcher.getListener(), launchEnv), "pull");
        argb.add("levoai/levo:stable");
        runAndParseOutput(launcher, launchEnv, argb, PULL_TIMEOUT);
    }

    public static void runLevoLogin(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, String workdir, Secret authorizationKey, String organizationId, @Nullable String baseUrl) throws IOException, InterruptedException {
        if (authorizationKey == null || authorizationKey.getPlainText() == null || authorizationKey.getPlainText().trim().isEmpty()) {
            throw new IllegalArgumentException("Authorization key is missing or empty");
        }
        if (organizationId == null || organizationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Organization ID is missing or empty");
        }
        
        // Clean up any existing credentials before login to ensure fresh authentication
        cleanupCredentials(workdir, launcher.getListener());
        
        // Pull latest Docker image to ensure we're using the most recent version
        launcher.getListener().getLogger().println("Pulling latest Levo CLI image...");
        runDockerPull(run, launcher, launchEnv);
        
        String apiKey = authorizationKey.getPlainText();
        // Log masked version for security (show first 3 and last 3 chars)
        String maskedKey = apiKey.length() > 6 
            ? apiKey.substring(0, 3) + "..." + apiKey.substring(apiKey.length() - 3)
            : "***";
        
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, null, workdir, baseUrl);
        argb.add("login", "-k", apiKey, "-o", organizationId);

        launcher.getListener().getLogger().println("Starting launch for: docker run ... login -k " + maskedKey + " -o " + organizationId);
        Launcher.ProcStarter procStarter = launcher.launch();
        int exitCode = procStarter.quiet(false).cmds(argb).envs(launchEnv).stdout(launcher.getListener().getLogger()).stderr(launcher.getListener().getLogger()).start().joinWithTimeout((long)CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
        
        if (exitCode != 0) {
            throw new IOException("Levo login failed with exit code: " + exitCode + ". Please verify your API key and organization ID are correct.");
        }
    }

    public static void runLevoConformanceTest(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir, String target, String schema) throws IOException, InterruptedException {
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, buildEnv, workdir, null);

        argb.add("test-conformance", "--target-url", target, "--schema", schema);

        launcher.getListener().getLogger().println("Starting launch for: " + argb.toString());
        Launcher.ProcStarter procStarter = launcher.launch();
        try {
            procStarter.quiet(false).cmds(argb).envs(launchEnv).stdout(launcher.getListener().getLogger()).stderr(launcher.getListener().getLogger()).start().joinWithTimeout((long)CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
            afterRunCleanUp(workdir);
        } catch (InterruptedException e) {
            // Job aborted
            afterRunCleanUp(workdir);
            throw e;
        }
    }

    public static void runLevoTestPlan(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir, String target, String testPlan, @Nullable String appName, @Nullable String env, @Nullable String categories, @Nullable String environment, Boolean generateJUnitReports, String extraCLIArgs, @Nullable String organizationId, @Nullable String baseUrl) throws IOException, InterruptedException {
        runDockerPull(run, launcher, launchEnv);
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, buildEnv, workdir, baseUrl);

        argb.add("test");
        
        // Add organization ID as CLI flag (required for backend to propagate OrganizationId header)
        if (organizationId != null && !organizationId.trim().isEmpty()) {
            argb.add("--organization", organizationId);
        }
        
        // Support app-name mode (mutually exclusive with test-plan)
        if (appName != null && !appName.trim().isEmpty()) {
            if (testPlan != null && !testPlan.trim().isEmpty()) {
                throw new IllegalArgumentException("--app-name cannot be used with --test-plan");
            }
            argb.add("--app-name", appName);
            if (env == null || env.trim().isEmpty()) {
                throw new IllegalArgumentException("--env is required when using --app-name");
            }
            argb.add("--env", env);
            if (categories != null && !categories.trim().isEmpty()) {
                argb.add("--categories", categories);
            }
        } else if (testPlan != null && !testPlan.trim().isEmpty()) {
            // Use existing test-plan mode
            argb.add("--test-plan", testPlan);
        } else {
            throw new IllegalArgumentException("One of --test-plan or --app-name must be provided");
        }
        
        // Target URL is required for all modes
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("--target-url is required");
        }
        argb.add("--target-url", target);
        
        if (generateJUnitReports != null && generateJUnitReports) {
            argb.add("--export-junit-xml=/home/levo/reports/junit.xml");
        }
        if (environment != null) {
            Path envPath = Paths.get(workdir, ENV_FILE_NAME);
            if (Files.exists(envPath)) {
                Files.delete(envPath);
            }
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(envPath.toString()), StandardCharsets.UTF_8))) {
                writer.append(environment);
                argb.add("--env-file", ENV_FILE_NAME);
            }
        }
        if (extraCLIArgs != null && !extraCLIArgs.isEmpty()) {
            argb.addTokenized(extraCLIArgs);
        }
        launcher.getListener().getLogger().println("Starting launch for: " + argb.toString());
        Launcher.ProcStarter procStarter = launcher.launch();
        try {
            procStarter.quiet(false).cmds(argb).envs(launchEnv).stdout(launcher.getListener().getLogger()).stderr(launcher.getListener().getLogger()).start().joinWithTimeout((long)CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
        } finally {
            // Always clean up credentials after run
            afterRunCleanUp(workdir);
        }
    }

    public static void runLevoRemoteTestRun(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir,
                                             String appName, String environment, String categories, String httpMethods, String excludeMethods,
                                             String endpointPattern, String excludeEndpointPattern, String testUsers, String targetUrl,
                                             String testMode, String runOn,
                                             String failSeverity, String failScope, String failThreshold,
                                             Secret authorizationKey, String organizationId, @Nullable String baseUrl) throws IOException, InterruptedException {
        runDockerPull(run, launcher, launchEnv);
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, buildEnv, workdir, baseUrl);

        argb.add("remote-test-run");
        
        // Required parameters
        if (appName != null && !appName.trim().isEmpty()) {
            argb.add("--app-name", appName);
        }
        if (environment != null && !environment.trim().isEmpty()) {
            argb.add("--env", environment);
        }
        
        // Required: Test Mode
        if (testMode == null || testMode.trim().isEmpty()) {
            throw new IllegalArgumentException("--test-mode is required");
        }
        argb.add("--test-mode", testMode);
        
        // Required: Run On
        if (runOn == null || runOn.trim().isEmpty()) {
            throw new IllegalArgumentException("--run-on is required");
        }
        // Normalize on-premises variations
        String normalizedRunOn = runOn.trim();
        if ("onprem".equalsIgnoreCase(normalizedRunOn) || "on-premises".equalsIgnoreCase(normalizedRunOn)) {
            normalizedRunOn = "on-premises";
        }
        argb.add("--run-on", normalizedRunOn);
        
        // Optional filter parameters
        if (categories != null && !categories.trim().isEmpty()) {
            argb.add("--categories", categories);
        }
        if (httpMethods != null && !httpMethods.trim().isEmpty()) {
            argb.add("--methods", httpMethods);
        }
        if (excludeMethods != null && !excludeMethods.trim().isEmpty()) {
            argb.add("--exclude-methods", excludeMethods);
        }
        if (endpointPattern != null && !endpointPattern.trim().isEmpty()) {
            argb.add("--endpoint-pattern", endpointPattern);
        }
        if (excludeEndpointPattern != null && !excludeEndpointPattern.trim().isEmpty()) {
            argb.add("--exclude-endpoint-pattern", excludeEndpointPattern);
        }
        // Parse comma-separated test users and add --test-users flag (only used in DataDriven mode)
        if (testUsers != null && !testUsers.trim().isEmpty()) {
            argb.add("--test-users", testUsers);
        }
        // Target URL is required
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("--target-url is required for remote-test-run");
        }
        argb.add("--target-url", targetUrl);
        
        // Failure criteria
        if (failSeverity != null && !failSeverity.trim().isEmpty() && !"none".equals(failSeverity)) {
            argb.add("--fail-severity", failSeverity);
        }
        if (failScope != null && !failScope.trim().isEmpty() && !"none".equals(failScope)) {
            argb.add("--fail-scope", failScope);
        }
        if (failThreshold != null && !failThreshold.trim().isEmpty()) {
            argb.add("--fail-threshold", failThreshold);
        }
        
        // Authentication (required)
        argb.add("--key", authorizationKey.getPlainText());
        argb.add("--organization", organizationId);
        
        // Verbosity
        argb.add("--verbosity", "INFO");

        launcher.getListener().getLogger().println("Starting remote test run: " + argb.toString());
        Launcher.ProcStarter procStarter = launcher.launch();
        try {
            procStarter.quiet(false).cmds(argb).envs(launchEnv).stdout(launcher.getListener().getLogger()).stderr(launcher.getListener().getLogger()).start().joinWithTimeout((long)CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
        } finally {
            // Always clean up credentials after run
            afterRunCleanUp(workdir);
        }
    }

    /**
     * Clean up credentials directory to ensure fresh authentication on each run
     */
    private static void cleanupCredentials(String workdir, TaskListener listener) throws IOException {
        Path configPath = Paths.get(workdir, LEVO_CONFIG_FOLDER_NAME);
        if (Files.exists(configPath)) {
            try {
                // Delete all files in the config directory
                Files.walk(configPath)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Log but don't fail if cleanup has issues
                            if (listener != null) {
                                listener.getLogger().println("Warning: Could not delete " + path + ": " + e.getMessage());
                            }
                        }
                    });
                if (listener != null) {
                    listener.getLogger().println("Cleaned up existing credentials for fresh authentication.");
                }
            } catch (IOException e) {
                // If walk fails, try simple delete
                try {
                    Files.delete(configPath);
                } catch (IOException e2) {
                    if (listener != null) {
                        listener.getLogger().println("Warning: Could not clean up credentials directory: " + e2.getMessage());
                    }
                }
            }
        }
    }

    private static void afterRunCleanUp(String workdir) throws IOException {
        Path envPath = Paths.get(workdir, ENV_FILE_NAME);
        Path configPath = Paths.get(workdir, LEVO_CONFIG_FOLDER_NAME);
        if (Files.exists(envPath)) {
            Files.delete(envPath);
        }
        // Clean up credentials after run
        if (Files.exists(configPath)) {
            try {
                Files.walk(configPath)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore cleanup errors
                        }
                    });
            } catch (IOException e) {
                // If walk fails, try simple delete
                try {
                    Files.delete(configPath);
                } catch (IOException e2) {
                    // Ignore cleanup errors
                }
            }
        }
    }
}
