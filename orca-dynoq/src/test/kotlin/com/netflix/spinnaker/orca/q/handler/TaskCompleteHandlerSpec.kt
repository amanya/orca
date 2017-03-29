/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.natpryce.hamkrest.allElements
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.netflix.spinnaker.orca.ExecutionStatus.*
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.model.Task
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.q.*
import com.netflix.spinnaker.orca.q.Message.*
import com.nhaarman.mockito_kotlin.*
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import java.time.Clock.fixed
import java.time.Instant.now
import java.time.ZoneId.systemDefault

@RunWith(JUnitPlatform::class)
class TaskCompleteHandlerSpec : Spek({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val clock = fixed(now(), systemDefault())

  val handler = TaskCompleteHandler(queue, repository, clock)

  fun resetMocks() = reset(queue, repository)

  describe("when a task completes successfully") {
    describe("the stage contains further tasks") {
      val pipeline = pipeline {
        stage {
          type = multiTaskStage.type
          multiTaskStage.buildTasks(this)
        }
      }
      val message = TaskComplete(Pipeline::class.java, pipeline.id, pipeline.stages.first().id, "1", SUCCEEDED)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the worker polls the queue") {
        handler.handle(message)
      }

      it("updates the task state in the stage") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.tasks.first().apply {
            assertThat(status, equalTo(SUCCEEDED))
            assertThat(endTime, equalTo(clock.millis()))
          }
        }
      }

      it("runs the next task") {
        verify(queue)
          .push(TaskStarting(
            Pipeline::class.java,
            message.executionId,
            message.stageId,
            "2"
          ))
      }
    }

    describe("the stage is complete") {
      val pipeline = pipeline {
        stage {
          type = singleTaskStage.type
          singleTaskStage.buildTasks(this)
        }
      }
      val message = TaskComplete(Pipeline::class.java, pipeline.id, pipeline.stages.first().id, "1", SUCCEEDED)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the worker polls the queue") {
        handler.handle(message)
      }

      it("updates the task state in the stage") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.tasks.last().apply {
            assertThat(status, equalTo(SUCCEEDED))
            assertThat(endTime, equalTo(clock.millis()))
          }
        }
      }

      it("emits an event to signal the stage is complete") {
        verify(queue)
          .push(StageComplete(
            message.executionType,
            message.executionId,
            message.stageId,
            SUCCEEDED
          ))
      }
    }

    context("the stage has synthetic after stages") {
      val pipeline = pipeline {
        stage {
          type = stageWithSyntheticAfter.type
          stageWithSyntheticAfter.buildTasks(this)
          stageWithSyntheticAfter.buildSyntheticStages(this)
        }
      }
      val message = TaskComplete(Pipeline::class.java, pipeline.id, pipeline.stages.first().id, "1", SUCCEEDED)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the worker polls the queue") {
        handler.handle(message)
      }

      it("updates the task state in the stage") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.tasks.last().apply {
            assertThat(status, equalTo(SUCCEEDED))
            assertThat(endTime, equalTo(clock.millis()))
          }
        }
      }

      it("triggers the first after stage") {
        verify(queue)
          .push(StageStarting(
            message.executionType,
            message.executionId,
            pipeline.stages[1].id
          ))
      }
    }

    describe("the task is the end of a rolling push loop") {
      val pipeline = pipeline {
        stage {
          refId = "1"
          type = rollingPushStage.type
          rollingPushStage.buildTasks(this)
        }
      }

      context("when the task returns REDIRECT") {
        val message = TaskComplete(Pipeline::class.java, pipeline.id, pipeline.stageByRef("1").id, "4", REDIRECT)

        beforeGroup {
          pipeline.stageByRef("1").apply {
            tasks[0].status = SUCCEEDED
            tasks[1].status = SUCCEEDED
            tasks[2].status = SUCCEEDED
          }

          whenever(repository.retrievePipeline(pipeline.id))
            .thenReturn(pipeline)
        }

        afterGroup(::resetMocks)

        action("the worker polls the queue") {
          handler.handle(message)
        }

        it("repeats the loop") {
          argumentCaptor<TaskStarting>().apply {
            verify(queue).push(capture())
            assertThat(firstValue.taskId, equalTo("2"))
          }
        }

        it("resets the status of the loop tasks") {
          argumentCaptor<Stage<Pipeline>>().apply {
            verify(repository).storeStage(capture())
            assertThat(firstValue.tasks[1..3].map(Task::getStatus), allElements(equalTo(NOT_STARTED)))
          }
        }
      }
    }

  }

  setOf(TERMINAL, CANCELED).forEach { status ->
    describe("when a task completes with $status status") {
      val pipeline = pipeline {
        stage {
          type = multiTaskStage.type
          multiTaskStage.buildTasks(this)
        }
      }
      val message = TaskComplete(Pipeline::class.java, pipeline.id, pipeline.stages.first().id, "1", status)

      beforeGroup {
        whenever(repository.retrievePipeline(message.executionId))
          .thenReturn(pipeline)
      }

      afterGroup(::resetMocks)

      action("the worker polls the queue") {
        handler.handle(message)
      }

      it("updates the task state in the stage") {
        argumentCaptor<Stage<Pipeline>>().apply {
          verify(repository).storeStage(capture())
          firstValue.tasks.first().apply {
            assertThat(status, equalTo(status))
            assertThat(endTime, equalTo(clock.millis()))
          }
        }
      }

      it("fails the stage") {
        verify(queue).push(StageComplete(
          message.executionType,
          message.executionId,
          message.stageId,
          status
        ))
      }

      it("does not run the next task") {
        verify(queue, never()).push(any<Message.RunTask>())
      }
    }
  }
})
