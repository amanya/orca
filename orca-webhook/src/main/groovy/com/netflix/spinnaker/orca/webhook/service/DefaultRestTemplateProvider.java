/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.orca.webhook.service;

import com.netflix.spinnaker.orca.webhook.pipeline.WebhookStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Order
@Component
public class DefaultRestTemplateProvider implements RestTemplateProvider<WebhookStage.StageData> {
  private final RestTemplate restTemplate;

  @Autowired
  public DefaultRestTemplateProvider(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  @Override
  public boolean supports(String targetUrl, WebhookStage.StageData stageData) {
    return true;
  }

  @Override
  public RestTemplate getRestTemplate(String targetUrl) {
    return restTemplate;
  }

  @Override
  public Class<WebhookStage.StageData> getStageDataType() {
    return WebhookStage.StageData.class;
  }
}
