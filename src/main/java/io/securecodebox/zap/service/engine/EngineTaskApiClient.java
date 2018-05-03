/*
 *
 *  *
 *  * SecureCodeBox (SCB)
 *  * Copyright 2015-2018 iteratec GmbH
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * 	http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package io.securecodebox.zap.service.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.securecodebox.zap.configuration.ZapConfiguration;
import io.securecodebox.zap.service.engine.model.CompleteTask;
import io.securecodebox.zap.service.engine.model.zap.ZapTask;
import io.securecodebox.zap.service.engine.model.zap.ZapTopic;
import io.securecodebox.zap.util.BasicAuthRestTemplate;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


/**
 * Consumes and integrates the Camunda process engine API for external tasks.
 */
@Service
@Slf4j
@ToString
public class EngineTaskApiClient {
    private final ZapConfiguration config;
    private RestTemplate restTemplate;


    @Autowired
    public EngineTaskApiClient(ZapConfiguration config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {

        log.info("initiating REST template for user {}", config.getCamundaUsername());

        restTemplate = (config.getCamundaUsername() != null && config.getCamundaPassword() != null)
                ? new BasicAuthRestTemplate(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()), config.getCamundaUsername(), config.getCamundaPassword())
                : new RestTemplate(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));

        restTemplate.setInterceptors(Collections.singletonList(new LoggingRequestInterceptor()));

        log.info("EngineApiClient is using {} as Engine Base URL.", config.getProcessEngineApiUrl());
    }

    int getTaskCountByTopic(ZapTopic topicName) {
        String url = config.getProcessEngineApiUrl() + "/count?topicName=" + topicName;
        log.debug("Call getTaskCountByTopic() via {}", url);

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        Map<String, Object> result = jsonStringToMap(response.getBody());

        if (response.getStatusCode().is2xxSuccessful() && response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)) {
            if (result.size() == 1 && result.containsKey("count")) {
                return Integer.parseInt(result.get("count").toString());
            } else {
                throw new ResourceAccessException("Status Code");
            }
        } else {
            log.error("HTTP response error: {}", response.getStatusCode());
            throw new ResourceAccessException("Status Code");
        }
    }

    ZapTask fetchAndLockTask(ZapTopic zapTopic, String jobId) {
        String url = config.getProcessEngineApiUrl() + "/box/jobs/lock/" + zapTopic.getName() + "/" + jobId;
        log.info(String.format("Trying to fetch task for the topic: %s via %s", zapTopic, url));

        ResponseEntity<ZapTask> task = restTemplate.postForEntity(url, null, ZapTask.class);
        if (task.getBody() != null && task.getStatusCode().is2xxSuccessful() && task.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)) {
            log.debug("HTTP Response Success");
        } else {
            log.debug("Currently nothing todo, no task found!");
        }
        return task.getBody();
    }

    void completeTask(CompleteTask task) {

        String url = config.getProcessEngineApiUrl() + "/box/jobs/" + task.getJobId() + "/result";
        log.info("Post completeTask({}) via {}", task, url);

        ResponseEntity<String> completedTask = restTemplate.postForEntity(url, task, String.class);
        log.info(String.format("Completed the task: %s", task.getJobId()));

        HttpStatus statusCode = completedTask.getStatusCode();

        if (statusCode.is2xxSuccessful()) {
            log.info(String.format("Successfully completed the task: %s: ", task.getJobId()));
        } else {
            log.error(String.format("Couldn't complete the task: %s, the return code is: %s with result: %s", task.getJobId(), statusCode, completedTask.getBody()));
        }
    }


    private static Map<String, Object> jsonStringToMap(String json) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = new HashMap<>(16);

        try {
            map = mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            log.error("Couldn't parse object to map", e);
        }
        return map;
    }
}
