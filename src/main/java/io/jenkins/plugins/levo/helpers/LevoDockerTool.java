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
import hudson.model.Node;
import hudson.model.Run;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import org.jenkinsci.plugins.docker.commons.tools.DockerTool;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LevoDockerTool {
    public static int CLIENT_TIMEOUT = 1800;

    private static ArgumentListBuilder buildLevoCommand(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir) throws IOException, InterruptedException {
        Node currentNode = run.getExecutor().getOwner().getNode();
        ArgumentListBuilder argb = new ArgumentListBuilder();
        argb.add(DockerTool.getExecutable(null, currentNode, launcher.getListener(), launchEnv), "run");

        Path levoConfigPath = Paths.get(workdir, ".levoconfig");
        if (!Files.exists(levoConfigPath)) {
            Files.createDirectory(levoConfigPath);
        }
        argb.add("-v", levoConfigPath + ":/home/levo/.config/configstore:rw");
        argb.add("-v", workdir + ":/home/levo/work:rw");

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

    public static void runLevoTestPlan(@NonNull Run run, @NonNull Launcher launcher, @NonNull EnvVars launchEnv, @Nullable EnvVars buildEnv, String workdir, String target, String testPlan, @Nullable String environment) throws IOException, InterruptedException {
        ArgumentListBuilder argb = buildLevoCommand(run, launcher, launchEnv, buildEnv, workdir);

        argb.add("test", "--target-url", target, "--test-plan", testPlan);

        if (environment != null) {
            Path envPath = Paths.get(workdir, "environment.yaml");
            if (Files.exists(envPath)) {
                Files.delete(envPath);
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(envPath.toString(), false))) {
                writer.append(environment);
                argb.add("--env-file", "environment.yaml");
            }
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
        Path envPath = Paths.get(workdir, "environment.yaml");
        Path configPath = Paths.get(workdir, ".levoconfig");
        Path hostEnvsPath = Paths.get(workdir, "host.env");
        Files.delete(envPath);
        Files.delete(configPath);
        Files.delete(hostEnvsPath);
    }
}
