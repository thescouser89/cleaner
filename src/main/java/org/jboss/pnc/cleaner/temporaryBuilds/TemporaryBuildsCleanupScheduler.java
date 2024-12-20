/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019-2022 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.cleaner.temporaryBuilds;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.scheduler.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Executes regular cleanup of old temporary builds after expiration.
 *
 * @author Jakub Bartecek
 */
@ApplicationScoped
public class TemporaryBuildsCleanupScheduler {

    private final Logger log = LoggerFactory.getLogger(TemporaryBuildsCleanupScheduler.class);

    @Inject
    TemporaryBuildsCleaner temporaryBuildsCleanupScheduleWorker;

    /**
     * Schedules cleanup of old temporary builds
     */
    @Scheduled(cron = "{temporaryBuildsCleaner.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @WithSpan
    public void cleanupExpiredTemporaryBuilds() {
        log.info("Regular deletion of temporary builds triggered by clock.");
        temporaryBuildsCleanupScheduleWorker.cleanupExpiredTemporaryBuilds();
        log.info("Regular deletion of temporary builds successfully finished.");
    }
}
