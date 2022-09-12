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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class LevoDockerTool {
    public static final int CLIENT_TIMEOUT = 1800;
    public static final int CMD_TIMEOUT = 60;
    public static final String ENV_FILE_NAME = "environment.yaml";
    public static final String LEVO_CONFIG_FOLDER_NAME = ".levoconfig";
    public static final String LEVO_REPORTS_FOLDER_NAME = "levo-reports";


    private static String runAndParseOutput(Launcher launcher, EnvVars envVars, ArgumentListBuilder cmd) throws IOException, InterruptedException {
        Launcher.ProcStarter procStarter = launcher.launch();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        procStarter.quiet(false)
                .cmds(cmd)
                .envs(envVars)
                .stdout(baos)
                .start()
                .joinWithTimeout(CMD_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
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

    private static ArgumentListBuilder buildLevoCommand(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir) throws IOException, InterruptedException {
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

        argb.add("levoai/levo:stable");

        return argb;
    }

    public static void runLevoLogin(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, String workdir, Secret authorizationKey, String organizationId) throws IOException, InterruptedException {
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, null, workdir);
        argb.add("login", "-k", authorizationKey.getPlainText(), "-o", organizationId);

        launcher.getListener().getLogger().println("Starting launch for: " + argb.toString());
        Launcher.ProcStarter procStarter = launcher.launch();
        procStarter.quiet(false).cmds(argb).envs(launchEnv).stdout(launcher.getListener().getLogger()).stderr(launcher.getListener().getLogger()).start().joinWithTimeout((long)CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
    }

    public static void runLevoConformanceTest(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir, String target, String schema) throws IOException, InterruptedException {
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, buildEnv, workdir);

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

    public static void runLevoTestPlan(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir, String target, String testPlan, @Nullable String environment, Boolean generateJUnitReports, String extraCLIArgs) throws IOException, InterruptedException {
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, buildEnv, workdir);

        argb.add("test", "--target-url", target, "--test-plan", testPlan);
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
        } catch (InterruptedException e) {
            // Job aborted
            afterRunCleanUp(workdir);
            throw e;
        }
    }

    private static void afterRunCleanUp(String workdir) throws IOException {
        Path envPath = Paths.get(workdir, ENV_FILE_NAME);
        Path configPath = Paths.get(workdir, LEVO_CONFIG_FOLDER_NAME);
        Files.delete(envPath);
        Files.delete(configPath);
    }
}
