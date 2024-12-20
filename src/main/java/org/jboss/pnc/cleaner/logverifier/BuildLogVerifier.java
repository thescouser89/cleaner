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
package org.jboss.pnc.cleaner.logverifier;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.bifrost.dto.MetaData;
import org.jboss.pnc.api.bifrost.enums.Direction;
import org.jboss.pnc.cleaner.orchApi.OrchClientProducer;
import org.jboss.pnc.client.BuildClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.common.otel.OtelUtils;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.rest.api.parameters.BuildsFilterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a> We don't need build-log-verifier anymore. Candidate for
 *         removal
 */
@ApplicationScoped
@Deprecated
public class BuildLogVerifier {

    private static final String className = BuildLogVerifier.class.getName();

    private final Logger logger = LoggerFactory.getLogger(BuildLogVerifier.class);
    private final Integer DEFAULT_BIFROST_BATCH_SIZE = 10000;

    @Inject
    @RestClient
    BifrostClient bifrost;

    @Inject
    BuildClient buildClient;

    @Inject
    OrchClientProducer orchClientProducer;

    @ConfigProperty(name = "buildLogVerifierScheduler.maxRetries")
    private Integer maxRetries;

    private final Map<String, AtomicInteger> buildESLogErrorCounter = new HashMap<>();

    public static final String BUILD_OUTPUT_OK_KEY = "BUILD_OUTPUT_OK";

    @Inject
    MeterRegistry registry;

    private Counter errCounter;
    private Counter warnCounter;

    @PostConstruct
    void initMetrics() {
        errCounter = registry.counter(className + ".error.count");
        warnCounter = registry.counter(className + ".warning.count");
    }

    public BuildLogVerifier() {
    }

    @Timed
    public int verifyUnflaggedBuilds() {

        logger.info("Verifying log checksums ...");
        Collection<Build> unverifiedBuilds = getUnverifiedBuilds().getAll();
        logger.info("Found {} unverified builds.", unverifiedBuilds.size());
        unverifiedBuilds.forEach(build -> verify(build.getId(), build.getBuildOutputChecksum()));
        return unverifiedBuilds.size();
    }

    @Timed
    void verify(String buildId, String checksum) {

        try {
            logger.info("Verifying log for build id: {}", buildId);
            String esChecksum = getESChecksum(buildId);
            if (checksum.equals(esChecksum)) {
                logger.info("Build output checksum OK. BuildId: {}, Checksum: {}.", buildId, checksum);
                flagPncBuild(buildId, true);

                removeRetryCounter(buildId);
            } else {
                warnCounter.increment();
                logger.warn(
                        "Build output checksum MISMATCH. BuildId: {}, Db checksum: {}, ElasticSearch checksum {}.",
                        buildId,
                        checksum,
                        esChecksum);

                handleMismatchWithRetries(buildId);
            }
        } catch (IOException e) {
            errCounter.increment();
            logger.error("Cannot verify checksum for buildId: " + buildId + ".", e);
        }
    }

    private void removeRetryCounter(String buildId) {
        buildESLogErrorCounter.remove(buildId);
    }

    @Timed
    void handleMismatchWithRetries(String buildId) {
        if (!buildESLogErrorCounter.containsKey(buildId)) {
            buildESLogErrorCounter.put(buildId, new AtomicInteger(0));
        }

        AtomicInteger numOfRetries = buildESLogErrorCounter.get(buildId);
        if (numOfRetries.get() >= maxRetries) {
            warnCounter.increment();
            logger.warn("Marking build with id: {} as mismatch", buildId);
            flagPncBuild(buildId, false);
            removeRetryCounter(buildId);
            return;
        }

        warnCounter.increment();
        logger.warn("Increasing retry counter (counter: {}) for build with id: {}", numOfRetries, buildId);
        buildESLogErrorCounter.get(buildId).incrementAndGet();
    }

    @Timed
    String getESChecksum(String buildId) throws IOException {
        String matchFilters = "mdc.processContext.keyword:build-" + buildId + ","
                + "loggerName.keyword:org.jboss.pnc._userlog_.build-log";

        MetaData metaData = bifrost
                .getMetaData(matchFilters, null, null, Direction.ASC, null, DEFAULT_BIFROST_BATCH_SIZE);
        return metaData.getMd5Digest();
    }

    private void flagPncBuild(String buildId, boolean checksumMatch) {
        try (BuildClient buildClientAuthenticated = orchClientProducer.getAuthenticatedBuildClient()) {
            buildClientAuthenticated.addAttribute(buildId, BUILD_OUTPUT_OK_KEY, Boolean.toString(checksumMatch));
        } catch (RemoteResourceException e) {
            errCounter.increment();
            logger.error("Cannot set {} attribute to build id: {}.", checksumMatch, buildId);
        }
    }

    @Timed
    RemoteCollection<Build> getUnverifiedBuilds() {
        BuildsFilterParameters buildsFilterParameters = new BuildsFilterParameters();
        buildsFilterParameters.setRunning(false);
        List<String> attributes = Collections.singletonList("!" + BUILD_OUTPUT_OK_KEY);
        try {
            String query = "buildOutputChecksum!=null";
            return buildClient.getAll(buildsFilterParameters, attributes, Optional.empty(), Optional.of(query));
        } catch (RemoteResourceException e) {
            errCounter.increment();
            logger.error("Cannot read remote builds.", e);
            return RemoteCollection.empty();
        }
    }
}
