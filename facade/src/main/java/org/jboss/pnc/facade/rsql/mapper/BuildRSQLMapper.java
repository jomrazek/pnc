/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2019 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.facade.rsql.mapper;

import org.jboss.pnc.model.BuildRecord;
import org.jboss.pnc.model.BuildRecord_;
import org.jboss.pnc.model.GenericEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.metamodel.SingularAttribute;

/**
 *
 * @author Honza Brázdil &lt;jbrazdil@redhat.com&gt;
 */
@ApplicationScoped
public class BuildRSQLMapper extends AbstractRSQLMapper<Integer, BuildRecord> {

    public BuildRSQLMapper() {
        super(BuildRecord.class);
    }

    @Override
    protected SingularAttribute<BuildRecord, ? extends GenericEntity<Integer>> toEntity(String name) {
        switch (name) {
            case "environment": return BuildRecord_.buildEnvironment;
            case "user": return BuildRecord_.user;
            case "groupBuild": return BuildRecord_.buildConfigSetRecord;
            default: return null;
        }
    }

    @Override
    protected SingularAttribute<BuildRecord, ?> toAttribute(String name) {
        switch (name) {
            case "id": return BuildRecord_.id;
            case "submitTime": return BuildRecord_.submitTime;
            case "startTime": return BuildRecord_.startTime;
            case "endTime": return BuildRecord_.endTime;
            case "status": return BuildRecord_.status;
            case "buildContentId": return BuildRecord_.buildContentId;
            case "temporaryBuild": return BuildRecord_.temporaryBuild;
            case "scmUrl": return BuildRecord_.scmRepoURL;
            case "scmTag": return BuildRecord_.scmTag;
            case "scmRevision": return BuildRecord_.scmRevision;
            default: return null;
        }
    }

}
