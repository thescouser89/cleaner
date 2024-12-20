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

/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
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
import io.micrometer.core.annotation.Timed;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.scheduler.Scheduled;

import jakarta.inject.Inject;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a> We don't need build-log-verifier anymore. Candidate for
 *         removal
 */
@Deprecated
public class BuildLogVerifierScheduler {

    @Inject
    BuildLogVerifier buildLogVerifier;

    /**
     * We don't need build-log-verifier anymore. Candidate for removal
     */
    @Timed
    @Scheduled(cron = "{buildLogVerifierScheduler.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @WithSpan
    @Deprecated
    public void verifyBuildLogs() {
        buildLogVerifier.verifyUnflaggedBuilds();
    }

}
