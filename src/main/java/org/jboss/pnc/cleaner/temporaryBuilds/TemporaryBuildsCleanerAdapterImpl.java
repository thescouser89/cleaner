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

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.Config;
import org.jboss.pnc.cleaner.orchApi.OrchClientProducer;
import org.jboss.pnc.client.BuildClient;
import org.jboss.pnc.client.GroupBuildClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.response.DeleteOperationResult;
import org.jboss.pnc.dto.GroupBuild;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;

import static org.jboss.pnc.cleaner.archiver.BuildArchiver.BUILD_ARCHIVED;

/**
 * Implementation of an adapter providing high-level operations on Orchestrator REST API
 *
 * @author Jakub Bartecek
 */
@ApplicationScoped
@Slf4j
public class TemporaryBuildsCleanerAdapterImpl implements TemporaryBuildsCleanerAdapter {

    private static final String className = TemporaryBuildsCleanerAdapterImpl.class.getName();

    private String BASE_DELETE_BUILD_CALLBACK_URL;

    private String BASE_DELETE_BUILD_GROUP_CALLBACK_URL;

    @Inject
    Config config;

    @Inject
    BuildClient buildClient;

    @Inject
    GroupBuildClient groupBuildClient;

    @Inject
    BuildDeleteCallbackManager buildDeleteCallbackManager;

    @Inject
    BuildGroupDeleteCallbackManager buildGroupDeleteCallbackManager;

    @Inject
    MeterRegistry registry;

    @Inject
    OrchClientProducer orchClientProducer;

    private Counter errCounter;
    private Counter warnCounter;

    void initMetrics() {
        errCounter = registry.counter(className + ".error.count");
        warnCounter = registry.counter(className + ".warning.count");
    }

    @PostConstruct
    void init() {
        final String host = config.getValue("applicationUri", String.class);

        BASE_DELETE_BUILD_CALLBACK_URL = host + "/callbacks/delete/builds/";
        BASE_DELETE_BUILD_GROUP_CALLBACK_URL = host + "/callbacks/delete/group-builds/";

        initMetrics();
    }

    @Timed
    @Override
    public Collection<Build> findTemporaryBuildsOlderThan(Date expirationDate) {
        Collection<Build> buildsRest = new HashSet<>();

        try {
            RemoteCollection<Build> remoteCollection = buildClient
                    .getAllIndependentTempBuildsOlderThanTimestamp(expirationDate.getTime());
            boolean isWarning = false;
            for (Build build : remoteCollection) {
                if (!build.getAttributes().containsKey(BUILD_ARCHIVED)) {
                    log.warn("Not deleting Build " + build.getId() + ", because it's not archived.");
                    isWarning = true;
                    continue;
                }
                buildsRest.add(build);
            }
            if (isWarning) {
                warnCounter.increment();
            }
        } catch (RemoteResourceException e) {
            warnCounter.increment();
            log.warn(
                    "Querying of temporary builds from Orchestrator failed with [status: {}, errorResponse: {}]",
                    e.getStatus(),
                    e.getResponse().orElse(null));
            return buildsRest;
        }

        return buildsRest;
    }

    @Timed
    @Override
    public void deleteTemporaryBuild(String id) throws OrchInteractionException {
        buildDeleteCallbackManager.initializeHandler(id);
        try (BuildClient buildClientAuthenticated = orchClientProducer.getAuthenticatedBuildClient()) {
            buildClientAuthenticated.delete(id, BASE_DELETE_BUILD_CALLBACK_URL + id);
            DeleteOperationResult result = buildDeleteCallbackManager.await(id);

            if (result != null && result.getStatus() != null && result.getStatus().isSuccess()) {
                return;
            } else {
                errCounter.increment();
                throw new OrchInteractionException(
                        String.format(
                                "Deletion of a build %s failed! " + "Orchestrator"
                                        + " reported a failure: [status={}, message={}].",
                                result == null ? null : result.getStatus(),
                                result == null ? null : result.getMessage()));
            }

        } catch (RemoteResourceException e) {
            errCounter.increment();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format(
                            "Deletion of a build %s failed! The operation " + "failed with errorStatus=%s.",
                            id,
                            e.getStatus()),
                    e);
        } catch (InterruptedException e) {
            errCounter.increment();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format("Deletion of a build %s failed! Wait operation " + "failed with an exception.", id),
                    e);
        }

    }

    @Timed
    @Override
    public Collection<GroupBuild> findTemporaryGroupBuildsOlderThan(Date expirationDate) {
        Collection<GroupBuild> groupBuilds = new HashSet<>();
        try {
            RemoteCollection<GroupBuild> remoteCollection = groupBuildClient.getAll(
                    Optional.empty(),
                    Optional.of("temporaryBuild==TRUE;endTime<" + formatTimestampForRsql(expirationDate)));
            remoteCollection.forEach(build -> groupBuilds.add(build));

        } catch (RemoteResourceException e) {
            warnCounter.increment();
            log.warn(
                    "Querying of temporary group builds from Orchestrator failed with [status: {}, errorResponse: "
                            + "{}]",
                    e.getStatus(),
                    e.getResponse().orElse(null));
        }

        return groupBuilds;
    }

    @Timed
    @Override
    public void deleteTemporaryGroupBuild(String id) throws OrchInteractionException {
        buildGroupDeleteCallbackManager.initializeHandler(id);

        try (GroupBuildClient groupBuildClientAuthenticated = orchClientProducer.getAuthenticatedBuildGroupClient()) {
            groupBuildClientAuthenticated.delete(id, BASE_DELETE_BUILD_GROUP_CALLBACK_URL + id);
            DeleteOperationResult result = buildGroupDeleteCallbackManager.await(id);

            if (result != null && result.getStatus() != null && result.getStatus().isSuccess()) {
                return;
            } else {
                errCounter.increment();
                throw new OrchInteractionException(
                        String.format(
                                "Deletion of a group build %s failed! " + "Orchestrator"
                                        + " reported a failure: [status={}, message={}].",
                                result == null ? null : result.getStatus(),
                                result == null ? null : result.getMessage()));
            }

        } catch (RemoteResourceException e) {
            errCounter.increment();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format(
                            "Deletion of a group build %s failed! The operation " + "failed with errorMessage=%s.",
                            id,
                            e.getStatus()),
                    e);
        } catch (InterruptedException e) {
            errCounter.increment();
            buildDeleteCallbackManager.cancel(id);
            throw new OrchInteractionException(
                    String.format(
                            "Deletion of a group build %s failed! Wait operation " + "failed with an exception.",
                            id),
                    e);
        }
    }

    private String formatTimestampForRsql(Date expirationDate) {
        return DateTimeFormatter.ISO_DATE_TIME.withLocale(Locale.ROOT)
                .withZone(ZoneId.of("UTC"))
                .format(Instant.ofEpochMilli(expirationDate.getTime()));
    }
}
