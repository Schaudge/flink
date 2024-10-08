/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.rest.handler.legacy.utils;

import org.apache.flink.api.common.ArchivedExecutionConfig;

import java.util.Collections;
import java.util.Map;

/** Utility class for constructing an ArchivedExecutionConfig. */
public class ArchivedExecutionConfigBuilder {
    private String executionMode;
    private String restartStrategyDescription;
    private int maxParallelism;
    private int parallelism;
    private boolean objectReuseEnabled;
    private long periodicMaterializeIntervalMillis;
    private Map<String, String> globalJobParameters;

    public ArchivedExecutionConfigBuilder setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
        return this;
    }

    public ArchivedExecutionConfigBuilder setRestartStrategyDescription(
            String restartStrategyDescription) {
        this.restartStrategyDescription = restartStrategyDescription;
        return this;
    }

    public ArchivedExecutionConfigBuilder setParallelism(int parallelism) {
        this.parallelism = parallelism;
        return this;
    }

    public ArchivedExecutionConfigBuilder setMaxParallelism(int maxParallelism) {
        this.maxParallelism = maxParallelism;
        return this;
    }

    public ArchivedExecutionConfigBuilder setObjectReuseEnabled(boolean objectReuseEnabled) {
        this.objectReuseEnabled = objectReuseEnabled;
        return this;
    }

    public ArchivedExecutionConfigBuilder setGlobalJobParameters(
            Map<String, String> globalJobParameters) {
        this.globalJobParameters = globalJobParameters;
        return this;
    }

    public ArchivedExecutionConfigBuilder setPeriodicMaterializeIntervalMillis(
            long periodicMaterializeIntervalMillis) {
        this.periodicMaterializeIntervalMillis = periodicMaterializeIntervalMillis;
        return this;
    }

    public ArchivedExecutionConfig build() {
        return new ArchivedExecutionConfig(
                restartStrategyDescription != null ? restartStrategyDescription : "default",
                maxParallelism,
                parallelism,
                objectReuseEnabled,
                periodicMaterializeIntervalMillis,
                globalJobParameters != null
                        ? globalJobParameters
                        : Collections.<String, String>emptyMap());
    }
}
