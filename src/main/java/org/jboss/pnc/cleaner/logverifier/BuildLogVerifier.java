package org.jboss.pnc.cleaner.logverifier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.bifrost.dto.MetaData;
import org.jboss.pnc.api.bifrost.enums.Direction;
import org.jboss.pnc.client.BuildClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.rest.api.parameters.BuildsFilterParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class BuildLogVerifier {

    private final Logger logger = LoggerFactory.getLogger(BuildLogVerifier.class);

    @Inject
    @RestClient
    BifrostClient bifrost;

    @Inject
    BuildClient buildClient;

    @ConfigProperty(name = "buildLogVerifierScheduler.maxRetries")
    private Integer maxRetries;

    private final Map<String, AtomicInteger> buildESLogErrorCounter = new HashMap<>();

    public static final String BUILD_OUTPUT_OK_KEY = "BUILD_OUTPUT_OK";

    public BuildLogVerifier() {
    }

    public int verifyUnflaggedBuilds() {
        logger.info("Verifying log checksums ...");
        Collection<Build> unverifiedBuilds = getUnverifiedBuilds().getAll();
        logger.info("Found {} unverified builds.", unverifiedBuilds.size());
        unverifiedBuilds.forEach(build -> verify(build.getId(), build.getBuildOutputChecksum()));
        return unverifiedBuilds.size();
    }

    private void verify(String buildId, String checksum) {
        try {
            logger.debug("Verifying log for build id: {}", buildId);
            String esChecksum = getESChecksum(buildId);
            if (checksum.equals(esChecksum)) {
                logger.info("Build output checksum OK. BuildId: {}, Checksum: {}.", buildId, checksum);
                flagPncBuild(buildId, true);

                removeRetryCounter(buildId);
            } else {
                logger.warn(
                        "Build output checksum MISMATCH. BuildId: {}, Db checksum: {}, ElasticSearch checksum {}.",
                        buildId,
                        checksum,
                        esChecksum);

                handleMismatchWithRetries(buildId);
            }
        } catch (IOException e) {
            logger.error("Cannot verify checksum for buildId: " + buildId + ".", e);
        }
    }

    private void removeRetryCounter(String buildId) {
        buildESLogErrorCounter.remove(buildId);
    }

    private void handleMismatchWithRetries(String buildId) {
        if (!buildESLogErrorCounter.containsKey(buildId)) {
            buildESLogErrorCounter.put(buildId, new AtomicInteger(0));
        }

        AtomicInteger numOfRetries = buildESLogErrorCounter.get(buildId);
        if (numOfRetries.get() >= maxRetries) {
            logger.warn("Marking build with id: {} as mismatch", buildId);
            flagPncBuild(buildId, false);
            removeRetryCounter(buildId);
            return;
        }

        logger.warn("Increasing retry counter (counter: {}) for build with id: {}", numOfRetries, buildId);
        buildESLogErrorCounter.get(buildId).incrementAndGet();
    }

    private String getESChecksum(String buildId) throws IOException {
        String matchFilters = "mdc.processContext.keyword:build-" + buildId;
        String prefixFilters = "loggerName.keyword:org.jboss.pnc._userlog_.build-log";

        MetaData metaData = bifrost.getMetaData(matchFilters, prefixFilters, null, Direction.ASC, null);
        return metaData.getMd5Digest();
    }

    private void flagPncBuild(String buildId, boolean checksumMatch) {
        try {
            buildClient.addAttribute(buildId, BUILD_OUTPUT_OK_KEY, Boolean.toString(checksumMatch));
        } catch (RemoteResourceException e) {
            logger.error("Cannot set {} attribute to build id: {}.", checksumMatch, buildId);
        }
    }

    private RemoteCollection<Build> getUnverifiedBuilds() {
        BuildsFilterParameters buildsFilterParameters = new BuildsFilterParameters();
        buildsFilterParameters.setRunning(false);
        List<String> attributes = Collections.singletonList("!" + BUILD_OUTPUT_OK_KEY);
        try {
            String query = "buildOutputChecksum!=null";
            return buildClient.getAll(buildsFilterParameters, attributes, Optional.empty(), Optional.of(query));
        } catch (RemoteResourceException e) {
            logger.error("Cannot read remote builds.", e);
            return RemoteCollection.empty();
        }
    }
}
