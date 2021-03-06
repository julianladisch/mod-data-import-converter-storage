package org.folio.services;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.folio.dao.ProfileDao;
import org.folio.dataimport.util.OkapiConnectionParams;
import org.folio.dataimport.util.RestUtil;
import org.folio.dataimport.util.exception.ConflictException;
import org.folio.rest.jaxrs.model.EntityTypeCollection;
import org.folio.rest.jaxrs.model.ProfileAssociation;
import org.folio.rest.jaxrs.model.ProfileSnapshotWrapper;
import org.folio.rest.jaxrs.model.UserInfo;
import org.folio.services.association.CommonProfileAssociationService;
import org.folio.services.association.ProfileAssociationService;
import org.folio.services.util.EntityTypes;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generic implementation of the {@link ProfileService}
 *
 * @param <T> type of the entity
 * @param <S> type of the collection of T entities
 */
@SuppressWarnings("squid:CallToDeprecatedMethod")
public abstract class AbstractProfileService<T, S, D> implements ProfileService<T, S, D> {

  private static final Logger logger = LoggerFactory.getLogger(AbstractProfileService.class);
  private static final String GET_USER_URL = "/users?query=id==";
  private static final String DELETE_PROFILE_ERROR_MESSAGE = "Can not delete profile by id '%s' cause profile associated with other profiles";

  @Autowired
  private ProfileAssociationService profileAssociationService;
  private final EntityTypeCollection entityTypeCollection;

  protected AbstractProfileService() {
    List<String> entityTypeList = Arrays.stream(EntityTypes.values())
      .map(EntityTypes::getName)
      .collect(Collectors.toList());
    entityTypeCollection = new EntityTypeCollection()
      .withEntityTypes(entityTypeList)
      .withTotalRecords(entityTypeList.size());
  }

  @Autowired
  private ProfileDao<T, S> profileDao;

  @Autowired
  protected CommonProfileAssociationService associationService;

  @Override
  public Future<S> getProfiles(boolean showDeleted, boolean withRelations, String query, int offset, int limit, String tenantId) {
    return profileDao.getProfiles(showDeleted, query, offset, limit, tenantId)
      .compose(profilesCollection -> {
        if (withRelations) {
          return fetchRelationsForCollection(profilesCollection, tenantId);
        } else {
          return Future.succeededFuture(profilesCollection);
        }
      });
  }

  @Override
  public Future<Optional<T>> getProfileById(String id, boolean withRelations, String tenantId) {
    return profileDao.getProfileById(id, tenantId)
      .compose(profile -> {
        if (withRelations && profile.isPresent()) {
          return fetchRelations(profile.get(), tenantId).map(Optional::of);
        } else {
          return Future.succeededFuture(profile);
        }
      });
  }

  @Override
  public Future<T> saveProfile(D profile, OkapiConnectionParams params) {
    return setUserInfoForProfile(profile, params)
      .compose(profileWithInfo -> profileDao.saveProfile(setProfileId(profileWithInfo), params.getTenantId())
        .map(prepareAssociations((profile)))
        .compose(ar -> deleteRelatedAssociations(getProfileAssociationToDelete(profile), params.getTenantId()))
        .compose(ar -> saveRelatedAssociations(getProfileAssociationToAdd(profile), params.getTenantId()))
        .map(profileWithInfo));
  }

  private Future<Boolean> deleteRelatedAssociations(List<ProfileAssociation> profileAssociations, String tenantId) {
    if (profileAssociations.isEmpty()) {
      return Future.succeededFuture(true);
    }
    Future<Boolean> result = Future.future();
    List<Future> futureList = new ArrayList<>();
    profileAssociations.forEach(association -> futureList.add(profileAssociationService.delete(association.getMasterProfileId(),
      association.getDetailProfileId(),
      ProfileSnapshotWrapper.ContentType.fromValue(association.getMasterProfileType().name()),
      ProfileSnapshotWrapper.ContentType.fromValue(association.getDetailProfileType().name()), tenantId)));
    CompositeFuture.all(futureList).setHandler(ar -> {
      if (ar.succeeded()) {
        result.complete(true);
      } else {
        result.fail(ar.cause());
      }
    });
    return result;
  }

  private Future<Boolean> saveRelatedAssociations(List<ProfileAssociation> profileAssociations, String tenantId) {
    if (profileAssociations.isEmpty()) {
      return Future.succeededFuture(true);
    }
    Future<Boolean> result = Future.future();
    List<Future> futureList = new ArrayList<>();
    profileAssociations.forEach(association -> futureList.add(profileAssociationService.save(association,
      ProfileSnapshotWrapper.ContentType.fromValue(association.getMasterProfileType().name()),
      ProfileSnapshotWrapper.ContentType.fromValue(association.getDetailProfileType().name()), tenantId)));
    CompositeFuture.all(futureList).setHandler(ar -> {
      if (ar.succeeded()) {
        result.complete(true);
      } else {
        result.fail(ar.cause());
      }
    });
    return result;
  }

  @Override
  public Future<T> updateProfile(D profile, OkapiConnectionParams params) {
    return setUserInfoForProfile(profile, params)
      .compose(profileWithInfo -> profileDao.updateProfile(profileWithInfo, params.getTenantId()))
      .map(prepareAssociations((profile)))
      .compose(ar -> deleteRelatedAssociations(getProfileAssociationToDelete(profile), params.getTenantId()))
      .compose(ar -> saveRelatedAssociations(getProfileAssociationToAdd(profile), params.getTenantId()))
      .map(getProfile(profile));
  }

  public Future<Boolean> markProfileAsDeleted(String id, String tenantId) {
    return profileDao.isProfileAssociatedAsDetail(id, tenantId)
      .compose(isAssociated -> isAssociated
        ? Future.failedFuture(new ConflictException(String.format(DELETE_PROFILE_ERROR_MESSAGE, id)))
        : profileDao.markProfileAsDeleted(id, tenantId))
      .map(true);
  }

  @Override
  public Future<Boolean> isProfileExistByProfileName(T profile, String tenantId) {
    String profileName = getProfileName(profile);
    String profileId = getProfileId(profile);
    return profileDao.isProfileExistByName(profileName, profileId, tenantId);
  }

  @Override
  public Future<EntityTypeCollection> getEntityTypes() {
    return Future.succeededFuture(entityTypeCollection);
  }

  /**
   * Generates id and sets it to the Profile entity
   *
   * @param profile Profile
   * @return Profile with generated id
   */
  abstract T setProfileId(T profile);

  /**
   * Set UserInfo for the Profile entity
   *
   * @param profile Profile
   * @param params  {@link OkapiConnectionParams}
   * @return Profile with filled userInfo field
   */
  abstract Future<T> setUserInfoForProfile(D profile, OkapiConnectionParams params);

  /**
   * Returns name of specified profile
   *
   * @param profile - profile entity
   * @return - profile name
   */
  protected abstract String getProfileName(T profile);

  /**
   * Returns id of specified profile
   *
   * @param profile - profile entity
   * @return - profile id
   */
  protected abstract String getProfileId(T profile);

  protected abstract D prepareAssociations(D profileDto);

  /**
   * Returns type of profile
   *
   * @return - ContentType of profiles type
   */
  protected abstract ProfileSnapshotWrapper.ContentType getProfileContentType();

  protected abstract void setChildProfiles(T profile, List<ProfileSnapshotWrapper> childProfiles);

  protected abstract void setParentProfiles(T profile, List<ProfileSnapshotWrapper> parentProfiles);

  protected abstract List<T> getProfilesList(S profilesCollection);

  protected abstract List<ProfileAssociation> getProfileAssociationToAdd(D dto);

  protected abstract List<ProfileAssociation> getProfileAssociationToDelete(D dto);

  protected abstract T getProfile(D dto);

  /**
   * Load all related child profiles for existing profile
   *
   * @param profile  - profile entity
   * @param tenantId - tenant id
   * @return - List of all related child profiles
   */
  private Future<List<ProfileSnapshotWrapper>> fetchChildProfiles(T profile, String tenantId) {
    return associationService.findDetails(getProfileId(profile), getProfileContentType(), null, null, 0, 999, tenantId)
      .map(this::convertToProfileSnapshotWrapper);
  }

  /**
   * Load all related parent profiles for existing profile
   *
   * @param profile  - profile entity
   * @param tenantId - tenant id
   * @return - List of all related parent profiles
   */
  private Future<List<ProfileSnapshotWrapper>> fetchParentProfiles(T profile, String tenantId) {
    return associationService.findMasters(getProfileId(profile), getProfileContentType(), null, null, 0, 999, tenantId)
      .map(this::convertToProfileSnapshotWrapper);
  }

  /**
   * Fetch parent and child profiles for each profile in collection
   *
   * @param profilesCollection - profile collection entity
   * @return - profile collection with fetched relations
   */
  private Future<S> fetchRelationsForCollection(S profilesCollection, String tenantId) {
    List<T> profilesList = getProfilesList(profilesCollection);
    List<Future> futureList = new ArrayList<>();
    Future<S> result = Future.future();
    profilesList.forEach(profile ->
      futureList.add(fetchRelations(profile, tenantId)));
    CompositeFuture.all(futureList).setHandler(ar -> {
      if (ar.succeeded()) {
        result.complete(profilesCollection);
      } else {
        logger.error("Error during fetching related profiles", ar.cause());
        result.fail(ar.cause());
      }
    });
    return result;
  }

  /**
   * Fetch parent and child profiles for single profile
   *
   * @param profile - profile entity
   * @return - profile collection with fetched relations
   */
  private Future<T> fetchRelations(T profile, String tenantId) {
    List<Future> futureList = new ArrayList<>();
    Future<T> result = Future.future();
    futureList.add(fetchChildProfiles(profile, tenantId)
      .compose(childProfiles -> {
        setChildProfiles(profile, childProfiles);
        return Future.succeededFuture(profile);
      })
      .compose(v -> fetchParentProfiles(profile, tenantId))
      .compose(parentProfiles -> {
        setParentProfiles(profile, parentProfiles);
        return Future.succeededFuture(profile);
      }));
    CompositeFuture.all(futureList).setHandler(ar -> {
      if (ar.succeeded()) {
        result.complete(profile);
      } else {
        logger.error("Error during fetching related profiles", ar.cause());
        result.fail(ar.cause());
      }
    });
    return result;
  }

  private List<ProfileSnapshotWrapper> convertToProfileSnapshotWrapper(Optional<ProfileSnapshotWrapper> rootWrapper) {
    List<ProfileSnapshotWrapper> profileSnapshotWrappers = new ArrayList<>();
    rootWrapper.ifPresent(profileSnapshotWrapper -> profileSnapshotWrappers.addAll(profileSnapshotWrapper.getChildSnapshotWrappers()
      .stream()
      .map(JsonObject::mapFrom)
      .map(json -> json.mapTo(ProfileSnapshotWrapper.class))
      .collect(Collectors.toList())));
    return profileSnapshotWrappers;
  }

  /**
   * Finds user by user id and returns UserInfo
   *
   * @param userId user id
   * @param params Okapi connection params
   * @return Future with found UserInfo
   */
  Future<UserInfo> lookupUser(String userId, OkapiConnectionParams params) {
    Future<UserInfo> future = Future.future();
    RestUtil.doRequest(params, GET_USER_URL + userId, HttpMethod.GET, null)
      .setHandler(getUserResult -> {
        if (RestUtil.validateAsyncResult(getUserResult, future)) {
          JsonObject response = getUserResult.result().getJson();
          if (!response.containsKey("totalRecords") || !response.containsKey("users")) {
            future.fail("Error, missing field(s) 'totalRecords' and/or 'users' in user response object");
          } else {
            int recordCount = response.getInteger("totalRecords");
            if (recordCount > 1) {
              String errorMessage = "There are more then one user by requested user id : " + userId;
              logger.error(errorMessage);
              future.fail(errorMessage);
            } else if (recordCount == 0) {
              String errorMessage = "No user found by user id :" + userId;
              logger.error(errorMessage);
              future.fail(errorMessage);
            } else {
              JsonObject jsonUser = response.getJsonArray("users").getJsonObject(0);
              JsonObject userPersonalInfo = jsonUser.getJsonObject("personal");
              UserInfo userInfo = new UserInfo()
                .withFirstName(userPersonalInfo.getString("firstName"))
                .withLastName(userPersonalInfo.getString("lastName"))
                .withUserName(jsonUser.getString("username"));
              future.complete(userInfo);
            }
          }
        }
      });
    return future;
  }

}
