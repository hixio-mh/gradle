/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.launcher.daemon.server;

import java.util.List;

/**
 * Expires the daemon only if all children would expire the daemon.
 */
public class AllDaemonExpirationStrategy implements DaemonExpirationStrategy {
    private Iterable<DaemonExpirationStrategy> expirationStrategies;

    public AllDaemonExpirationStrategy(List<DaemonExpirationStrategy> expirationStrategies) {
        this.expirationStrategies = expirationStrategies;
    }

    @Override
    public DaemonExpirationResult checkExpiration(Daemon daemon) {
        DaemonExpirationResult expirationResult = new DaemonExpirationResult(false, null);
        for (DaemonExpirationStrategy expirationStrategy : expirationStrategies) {
            expirationResult = expirationStrategy.checkExpiration(daemon);
            if (!expirationResult.isExpired()) {
                return new DaemonExpirationResult(false, null);
            }
        }
        return expirationResult;
    }
}
