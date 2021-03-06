/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.facade.executor;

import org.jboss.pnc.bpm.BpmManager;
import org.jboss.pnc.bpm.BpmTask;
import org.jboss.pnc.bpm.model.ProcessProgressUpdate;
import org.jboss.pnc.bpm.task.BpmBuildTask;
import org.jboss.pnc.common.Date.ExpiresDate;
import org.jboss.pnc.common.json.moduleconfig.SystemConfig;
import org.jboss.pnc.common.logging.BuildTaskContext;
import org.jboss.pnc.enums.BPMTaskStatus;
import org.jboss.pnc.enums.BuildExecutionStatus;
import org.jboss.pnc.bpm.notification.BpmNotifier;
import org.jboss.pnc.spi.events.BuildExecutionStatusChangedEvent;
import org.jboss.pnc.spi.exception.CoreException;
import org.jboss.pnc.spi.executor.BuildExecutionConfiguration;
import org.jboss.pnc.spi.executor.BuildExecutionSession;
import org.jboss.pnc.spi.executor.BuildExecutor;
import org.jboss.pnc.spi.executor.exceptions.ExecutorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.URI;
import java.util.Optional;
import java.util.function.Consumer;

@ApplicationScoped
public class BuildExecutorTriggerer {

    private static final Logger log = LoggerFactory.getLogger(BuildExecutorTriggerer.class);

    private BuildExecutor buildExecutor;

    private BpmNotifier bpmNotifier;

    //TODO decouple executor
    @Deprecated
    BpmManager bpmManager;

    private SystemConfig systemConfig;

    @Deprecated //CDI workaround
    public BuildExecutorTriggerer() {}

    @Inject
    public BuildExecutorTriggerer(
            BuildExecutor buildExecutor,
            BpmNotifier bpmNotifier,
            BpmManager bpmManager,
            SystemConfig systemConfig) {

        this.buildExecutor = buildExecutor;
        this.bpmNotifier = bpmNotifier;
        this.bpmManager = bpmManager;
        this.systemConfig = systemConfig;
    }

    public BuildExecutionSession executeBuild(
            BuildExecutionConfiguration buildExecutionConfig,
            String callbackUrl,
            String accessToken)
            throws CoreException, ExecutorException {

        Consumer<BuildExecutionStatusChangedEvent> onExecutionStatusChange = (statusChangedEvent) -> {

            log.debug("Received BuildExecutionStatusChangedEvent: " + statusChangedEvent);

            Optional<ProcessProgressUpdate> processProgressUpdate = toProcessProgressUpdate(statusChangedEvent);

            if (processProgressUpdate.isPresent()) {

                //As there is a plan to split the Executor from Coordinator, the notification should be sent over http
                //to the endpoint /bpm/tasks/{taskId}/notify
                //bpmManager should be aupdated to accept notifications identified by buildTaskId
                Optional<BpmTask> bpmTask = BpmBuildTask.getBpmTaskByBuildTaskId(bpmManager,
                        statusChangedEvent.getBuildTaskId());

                if (bpmTask.isPresent()) {
                    bpmManager.notify(bpmTask.get().getTaskId(), processProgressUpdate.get());
                } else {
                    log.warn("There is no bpmTask for buildTask.id: " + statusChangedEvent.getBuildTaskId() + ". Skipping notification.");
                }

            }
            if (statusChangedEvent.isFinal() && callbackUrl != null && !callbackUrl.isEmpty()) {

                statusChangedEvent.getBuildResult().ifPresent((buildResult) -> {

                    bpmNotifier.sendBuildExecutionCompleted(callbackUrl, buildResult);
                });
            }
        };
        BuildExecutionSession buildExecutionSession =
                buildExecutor.startBuilding(buildExecutionConfig, onExecutionStatusChange, accessToken);

        return buildExecutionSession;
    }

    private Optional<ProcessProgressUpdate> toProcessProgressUpdate(BuildExecutionStatusChangedEvent statusChangedEvent) {
        BuildExecutionStatus status = statusChangedEvent.getNewStatus();

        String taskName = null;
        BPMTaskStatus bpmTaskStatus = BPMTaskStatus.STARTING;
        String wsDetails = "";

        switch (status) {
            case REPO_SETTING_UP:
                taskName = "Repository";
                break;

            case BUILD_ENV_SETTING_UP:
                taskName = "Environment";
                break;

            case BUILD_WAITING:
                taskName = "Build";
                bpmTaskStatus = BPMTaskStatus.STARTED;
                BuildExecutionSession runningExecution = buildExecutor.getRunningExecution(statusChangedEvent.getBuildTaskId());
                Optional<URI> liveLogsUri = runningExecution.getLiveLogsUri();
                if (liveLogsUri.isPresent()) {
                    wsDetails = liveLogsUri.get().toString();
                } else {
                    log.warn("Missing live log url for buildExecution: " + statusChangedEvent.getBuildTaskId());
                }
                break;

            case COLLECTING_RESULTS_FROM_BUILD_DRIVER:
                taskName = "Collecting results from build";
                break;

            case COLLECTING_RESULTS_FROM_REPOSITORY_MANAGER:
                taskName = "Collecting results from repository";
                break;

            case BUILD_ENV_DESTROYING:
                taskName = "Destroying environment";
                break;

            case FINALIZING_EXECUTION:
                taskName = "Finalizing";
                break;
        }

        if (taskName != null) {
            return Optional.of(new ProcessProgressUpdate(taskName, bpmTaskStatus, wsDetails));
        } else {
            return Optional.empty();
        }
    }

    public void cancelBuild(Integer buildExecutionConfigId) throws CoreException, ExecutorException {
        buildExecutor.cancel(buildExecutionConfigId);
    }

    public Optional<BuildTaskContext> getMdcMeta(Integer buildExecutionConfigId, String userId) {

        BuildExecutionSession runningExecution = buildExecutor.getRunningExecution(buildExecutionConfigId);

        if (runningExecution != null) {

            BuildExecutionConfiguration buildExecutionConfiguration = runningExecution.getBuildExecutionConfiguration();
            boolean temporaryBuild = buildExecutionConfiguration.isTempBuild();

            return Optional.of(new BuildTaskContext(
                    buildExecutionConfiguration.getBuildContentId(),
                    userId,
                    temporaryBuild,
                    ExpiresDate.getTemporaryBuildExpireDate(systemConfig.getTemporaryBuildsLifeSpan(), temporaryBuild)
            ));
        } else {
            return Optional.empty();
        }
    }
}
