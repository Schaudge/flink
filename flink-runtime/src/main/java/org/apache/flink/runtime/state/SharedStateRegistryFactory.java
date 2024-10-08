/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.state;

import org.apache.flink.core.execution.RecoveryClaimMode;
import org.apache.flink.runtime.checkpoint.CompletedCheckpoint;

import java.util.Collection;
import java.util.concurrent.Executor;

/** Simple factory to produce {@link SharedStateRegistry} objects. */
public interface SharedStateRegistryFactory {

    /**
     * Factory method for {@link SharedStateRegistry}.
     *
     * @param deleteExecutor executor used to run (async) deletes.
     * @param checkpoints whose shared state will be registered.
     * @param recoveryClaimMode the mode in which the given checkpoints were restored
     * @return a SharedStateRegistry object
     */
    SharedStateRegistry create(
            Executor deleteExecutor,
            Collection<CompletedCheckpoint> checkpoints,
            RecoveryClaimMode recoveryClaimMode);
}
