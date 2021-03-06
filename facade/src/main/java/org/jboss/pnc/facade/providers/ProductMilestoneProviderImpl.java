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
package org.jboss.pnc.facade.providers;

import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.pnc.dto.ProductMilestone;
import org.jboss.pnc.dto.ProductMilestoneRef;
import org.jboss.pnc.dto.response.Page;
import org.jboss.pnc.dto.validation.groups.WhenUpdating;
import org.jboss.pnc.mapper.api.ProductMilestoneMapper;
import org.jboss.pnc.facade.providers.api.ProductMilestoneProvider;
import org.jboss.pnc.facade.validation.ConflictedEntryException;
import org.jboss.pnc.facade.validation.ConflictedEntryValidator;
import org.jboss.pnc.facade.validation.EmptyEntityException;
import org.jboss.pnc.facade.validation.InvalidEntityException;
import org.jboss.pnc.facade.validation.RepositoryViolationException;
import org.jboss.pnc.facade.validation.ValidationBuilder;
import org.jboss.pnc.bpm.causeway.ProductMilestoneReleaseManager;
import org.jboss.pnc.spi.datastore.repositories.ProductMilestoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.Tuple;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.SetJoin;
import javax.persistence.metamodel.SetAttribute;
import org.jboss.pnc.dto.response.MilestoneInfo;
import org.jboss.pnc.facade.util.UserService;
import org.jboss.pnc.model.Artifact;
import org.jboss.pnc.model.Artifact_;
import org.jboss.pnc.model.BuildRecord;
import org.jboss.pnc.model.BuildRecord_;
import org.jboss.pnc.model.Product;
import org.jboss.pnc.model.ProductMilestone_;
import org.jboss.pnc.model.ProductRelease;
import org.jboss.pnc.model.ProductRelease_;
import org.jboss.pnc.model.ProductVersion;
import org.jboss.pnc.model.ProductVersion_;
import org.jboss.pnc.model.Product_;

import static org.jboss.pnc.spi.datastore.predicates.ProductMilestonePredicates.withProductVersionId;
import static org.jboss.pnc.spi.datastore.predicates.ProductMilestonePredicates.withProductVersionIdAndVersion;

@PermitAll
@Stateless
public class ProductMilestoneProviderImpl
        extends AbstractIntIdProvider<org.jboss.pnc.model.ProductMilestone, ProductMilestone, ProductMilestoneRef>
        implements ProductMilestoneProvider {

    private static final Logger log = LoggerFactory.getLogger(ProductMilestoneProviderImpl.class);

    private ProductMilestoneReleaseManager releaseManager;

    @Inject
    private UserService userService;

    @Inject
    private EntityManager em;

    @Inject
    public ProductMilestoneProviderImpl(ProductMilestoneRepository repository,
                                        ProductMilestoneMapper mapper,
                                        ProductMilestoneReleaseManager releaseManager) {

        super(repository, mapper, org.jboss.pnc.model.ProductMilestone.class);

        this.releaseManager = releaseManager;
    }

    @Override
    public ProductMilestone update(String id, ProductMilestone restEntity) {
        validateBeforeUpdating(id, restEntity);

        org.jboss.pnc.model.ProductMilestone milestoneInDb = repository.queryById(Integer.valueOf(id));
        org.jboss.pnc.model.ProductMilestone milestoneRestDb = mapper.toEntity(restEntity);

        // we can't modify milestone if it's already released
        if (milestoneInDb.getEndDate() != null) {
            log.info("Milestone is already closed: no more modifications allowed");
            throw new RepositoryViolationException("Milestone is already closed! No more modifications allowed");
        }

        log.debug("Updating milestone for id: {}", id);
        milestoneRestDb.setId(Integer.valueOf(id));

        // make sure that user cannot set the 'endDate' via the REST API
        // this should only be set after the release process is successful
        milestoneRestDb.setEndDate(milestoneInDb.getEndDate());

        validateBeforeUpdating(id, mapper.toDTO(milestoneRestDb));

        return mapper.toDTO(repository.save(milestoneRestDb));
    }

    @Override
    protected void validateBeforeSaving(ProductMilestone restEntity) {
        super.validateBeforeSaving(restEntity);
        validateDoesNotConflict(restEntity);
    }

    @Override
    protected void validateBeforeUpdating(String id, ProductMilestone restEntity) {
        super.validateBeforeUpdating(restEntity.getId(), restEntity);
        validateDoesNotConflict(restEntity);
    }

    private void validateDoesNotConflict(ProductMilestone restEntity)
            throws ConflictedEntryException, InvalidEntityException {
        ValidationBuilder.validateObject(restEntity, WhenUpdating.class).validateConflict(() -> {
            org.jboss.pnc.model.ProductMilestone milestoneFromDB = repository.queryByPredicates(
                    withProductVersionIdAndVersion(Integer.valueOf(restEntity.getProductVersion().getId()), restEntity.getVersion())
            );

            Integer restEntityId = null;

            if (restEntity.getId() != null) {
                restEntityId = Integer.valueOf(restEntity.getId());
            }

            // don't validate against myself
            if (milestoneFromDB != null && !milestoneFromDB.getId().equals(restEntityId)) {
                return new ConflictedEntryValidator.ConflictedEntryValidationError(
                        milestoneFromDB.getId(),
                        org.jboss.pnc.model.ProductMilestone.class,
                        "Product milestone with the same product version and version already exists");
            }
            return null;
        });
    }

    @Override
    public void closeMilestone(String id, ProductMilestone restEntity) {
        org.jboss.pnc.model.ProductMilestone milestoneInDb = repository.queryById(Integer.valueOf(id));

        if (milestoneInDb.getEndDate() != null) {
            log.info("Milestone is already closed: no more modifications allowed");
            throw new RepositoryViolationException("Milestone is already closed! No more modifications allowed");
        } else {
            // save download url if specified
            if (restEntity.getDownloadUrl() != null) {
                milestoneInDb.setDownloadUrl(restEntity.getDownloadUrl());
                repository.save(milestoneInDb);
            }

            if(releaseManager.noReleaseInProgress(milestoneInDb)) {
                log.debug("Milestone's 'end date' set; no release of the milestone in progress: will start release");
                releaseManager.startRelease(milestoneInDb, userService.currentUserToken());
            }
        }
    }

    @Override
    public void cancelMilestoneCloseProcess(String id)
            throws RepositoryViolationException, EmptyEntityException {

        org.jboss.pnc.model.ProductMilestone milestoneInDb = repository.queryById(Integer.valueOf(id));

        // If we want to close a milestone, make sure it's not already released (by checking end date)
        // and there are no release in progress
        if (milestoneInDb.getEndDate() != null) {

            log.info("Milestone is already closed.");
            throw new RepositoryViolationException("Milestone is already closed!");

        } else {

            if(releaseManager.noReleaseInProgress(milestoneInDb)) {

                log.debug("Milestone's 'end date' set and no release in progress! Cannot run cancel process for given id");
                throw new EmptyEntityException("No running cancel process for given id.");

            } else {

                releaseManager.cancel(milestoneInDb);
            }
        }
    }


    @Override
    public Page<ProductMilestone> getProductMilestonesForProductVersion(int pageIndex,
                                                                        int pageSize,
                                                                        String sortingRsql,
                                                                        String query,
                                                                        String productVersionId) {

        return queryForCollection(pageIndex, pageSize, sortingRsql, query, withProductVersionId(Integer.valueOf(productVersionId)));
    }

    @Override
    public Page<MilestoneInfo> getMilestonesOfArtifact(String id, int pageIndex, int pageSize) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        List<Integer> builtIn = getMilestoneIds(cb, id, Artifact_.buildRecords);
        List<Integer> dependencyOf = getMilestoneIds(cb, id, Artifact_.dependantBuildRecords);

        Set<Integer> milestoneIds = new HashSet<>(dependencyOf);
        milestoneIds.addAll(builtIn);
        milestoneIds.remove(null); // some builds are not in a milestone and so it gives us null
        if(milestoneIds.isEmpty()){
            return new Page<>();
        }

        CriteriaQuery<Tuple> query = milestoneInfoQuery(cb, milestoneIds);
        int offset = pageIndex * pageSize;
        List<MilestoneInfo> milestones = em.createQuery(query)
                .setMaxResults(pageSize)
                .setFirstResult(offset)
                .getResultList()
                .stream()
                .map(m -> mapTupleToMilestoneInfo(m, builtIn))
                .collect(Collectors.toList());

        return new Page<>(pageIndex,
                pageSize,
                milestoneIds.size(),
                milestones);
    }

    private CriteriaQuery<Tuple> milestoneInfoQuery(CriteriaBuilder cb, Set<Integer> milestoneIds) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();

        Root<org.jboss.pnc.model.ProductMilestone> milestone = query.from(org.jboss.pnc.model.ProductMilestone.class);
        Root<ProductRelease> release = query.from(ProductRelease.class);
        Path<ProductVersion> version = milestone.get(ProductMilestone_.productVersion);
        Path<Product> product = version.get(ProductVersion_.product);
        query.multiselect(
                product.get(Product_.id),
                product.get(Product_.name),
                version.get(ProductVersion_.id),
                version.get(ProductVersion_.version),
                milestone.get(ProductMilestone_.id),
                milestone.get(ProductMilestone_.version),
                milestone.get(ProductMilestone_.endDate),
                release.get(ProductRelease_.id),
                release.get(ProductRelease_.version),
                release.get(ProductRelease_.releaseDate));
        query.where(cb.and(
                cb.equal(release.get(ProductRelease_.productMilestone), milestone)),
                milestone.get(ProductMilestone_.id).in(milestoneIds));
        query.orderBy(cb.desc(milestone.get(ProductMilestone_.endDate)),
                cb.desc(milestone.get(ProductMilestone_.id)));
        return query;
    }

    private MilestoneInfo mapTupleToMilestoneInfo(Tuple tuple, List<Integer> buildIn) {
        final Integer milestoneId = (Integer) tuple.get(4);
        return MilestoneInfo.builder()
                .productId(tuple.get(0).toString())
                .productName(tuple.get(1).toString())
                .productVersionId(tuple.get(2).toString())
                .productVersionVersion(tuple.get(3).toString())
                .milestoneId(milestoneId.toString())
                .milestoneVersion(tuple.get(5).toString())
                .milestoneEndDate(toInstant(tuple.get(6)))
                .releaseId(tuple.get(7).toString())
                .releaseVersion(tuple.get(8).toString())
                .releaseReleaseDate(toInstant(tuple.get(9)))
                .built(buildIn.contains(milestoneId))
                .build();
    }

    private static Instant toInstant(Object object) {
        if(object== null)
            return null;
        return ((Date) object).toInstant();
    }

    private List<Integer> getMilestoneIds(CriteriaBuilder cb, String id, SetAttribute<Artifact, BuildRecord> buildRecords) {
        CriteriaQuery<Integer> buildQuery = cb.createQuery(Integer.class);

        Root<Artifact> artifact = buildQuery.from(Artifact.class);
        SetJoin<Artifact, BuildRecord> build = artifact.join(buildRecords);
        buildQuery.where(cb.equal(artifact.get(Artifact_.id), Integer.valueOf(id)));
        buildQuery.select(build.get(BuildRecord_.productMilestone).get(ProductMilestone_.id));
        buildQuery.distinct(true);

        List<Integer> resultList = em.createQuery(buildQuery).getResultList();
        return resultList;
    }
}
