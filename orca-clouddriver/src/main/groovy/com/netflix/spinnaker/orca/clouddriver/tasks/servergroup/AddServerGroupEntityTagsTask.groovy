/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.servergroup

import com.netflix.spinnaker.orca.api.operations.OperationsContext
import com.netflix.spinnaker.orca.api.operations.OperationsInput
import com.netflix.spinnaker.orca.api.operations.OperationsRunner
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution

import java.util.concurrent.TimeUnit
import com.fasterxml.jackson.annotation.JsonProperty
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.api.pipeline.RetryableTask
import com.netflix.spinnaker.orca.api.pipeline.TaskResult
import com.netflix.spinnaker.orca.clouddriver.utils.CloudProviderAware

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@Slf4j
class AddServerGroupEntityTagsTask implements CloudProviderAware, RetryableTask {
  long backoffPeriod = TimeUnit.SECONDS.toMillis(30)
  long timeout = TimeUnit.MINUTES.toMillis(15)

  @Autowired
  OperationsRunner operationsRunner

  @Autowired
  Collection<ServerGroupEntityTagGenerator> tagGenerators

  @Override
  TaskResult execute(StageExecution stage) {
    try {
      List<Map> tagOperations = buildTagOperations(stage)
      if (!tagOperations) {
        return TaskResult.ofStatus(ExecutionStatus.SUCCEEDED)
      }

      OperationsInput operationsInput = OperationsInput.of(tagOperations, stage)
      OperationsContext operationsContext = operationsRunner.run(operationsInput)

      return TaskResult.builder(ExecutionStatus.SUCCEEDED).context(new HashMap<String, Object>() {
        {
          put("notification.type", "upsertentitytags")
          put(operationsContext.contextKey(), operationsContext.contextValue())
        }
      }).build()
    } catch (Exception e) {
      log.error("Failed to tag deployed server groups (stageId: ${stage.id}, executionId: ${stage.execution.id})", e)
      return TaskResult.ofStatus(ExecutionStatus.FAILED_CONTINUE)
    }
  }

  private List<Map> buildTagOperations(StageExecution stage) {
    def operations = []
    ((StageData) stage.mapTo(StageData)).deployServerGroups.each { String region, Set<String> serverGroups ->
      serverGroups.each { String serverGroup ->
        Collection<Map<String, Object>> tags = tagGenerators ?
          tagGenerators.findResults {
            try {
              return it.generateTags(stage, serverGroup, getCredentials(stage), region, getCloudProvider(stage))
            } catch (Exception e) {
              log.error("TagGenerator ${it.class} failed for $serverGroup", e)
              return []
            }
          }.flatten() :
          []

        log.debug(
          "Generated entity tags (executionId: {}, serverGroup: {}, tagCount: {}, tags: {})",
          stage.execution.id,
          serverGroup,
          tags.size(),
          tags
        )

        if (!tags) {
          return []
        }

        operations <<
          [
            "upsertEntityTags": [
              isPartial: false,
              tags     : tags,
              entityRef: [
                entityType   : "servergroup",
                entityId     : serverGroup,
                account      : getCredentials(stage),
                region       : region,
                cloudProvider: getCloudProvider(stage)
              ]
            ]
          ]
      }
    }

    return operations
  }

  static class StageData {
    @JsonProperty("deploy.server.groups")
    Map<String, Set<String>> deployServerGroups = [:]
  }
}
