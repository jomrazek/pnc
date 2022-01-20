/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2022 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.dto.requests;

import org.jboss.pnc.dto.BuildConfiguration;
import org.jboss.pnc.dto.validation.constraints.SCMUrl;

import javax.validation.constraints.NotBlank;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import lombok.Builder;
import lombok.Data;

/**
 * Request to create new build config and SCM repository config given by SCM URL. If the URL is for external SCM, new
 * internal repository will be created and synced.
 *
 * @author Honza Brázdil &lt;jbrazdil@redhat.com&gt;
 */
@Data
@Builder(builderClassName = "Builder")
@JsonDeserialize(builder = BuildConfigWithSCMRequest.Builder.class)
public class BuildConfigWithSCMRequest {

    /**
     * The SCM repository URL. The URL can be internal or external.
     */
    @NotBlank
    @SCMUrl
    private final String scmUrl;

    /**
     * Pre-builds sync enablement flag. Is taken into account only when scmUrl contains an external URL. Defaults to
     * true.
     */
    private final Boolean preBuildSyncEnabled;

    /**
     * The build config to be created.
     */
    private final BuildConfiguration buildConfig;

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
    }
}
