package org.folio.rest.impl;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.rest.jaxrs.model.ActionProfile;
import org.folio.rest.jaxrs.model.ActionProfileUpdateDto;
import org.folio.rest.jaxrs.model.EntityType;
import org.folio.rest.jaxrs.model.MappingProfile;
import org.folio.rest.jaxrs.model.MappingProfileUpdateDto;
import org.folio.rest.jaxrs.model.ProfileAssociation;
import org.folio.rest.jaxrs.model.Tags;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.persist.PostgresClient;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.folio.rest.impl.ActionProfileTest.ACTION_PROFILES_PATH;
import static org.folio.rest.impl.ActionProfileTest.ACTION_PROFILES_TABLE_NAME;
import static org.folio.rest.jaxrs.model.ActionProfile.Action.CREATE;
import static org.folio.rest.jaxrs.model.ActionProfile.FolioRecord.MARC_BIBLIOGRAPHIC;
import static org.folio.rest.jaxrs.model.ProfileSnapshotWrapper.ContentType.ACTION_PROFILE;
import static org.folio.rest.jaxrs.model.ProfileSnapshotWrapper.ContentType.MAPPING_PROFILE;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class MappingProfileTest extends AbstractRestVerticleTest {

  static final String MAPPING_PROFILES_TABLE_NAME = "mapping_profiles";
  private static final String ACTION_TO_MAPPING_PROFILES_TABLE = "action_to_mapping_profiles";
  static final String MAPPING_PROFILES_PATH = "/data-import-profiles/mappingProfiles";
  private static final String ASSOCIATED_PROFILES_PATH = "/data-import-profiles/profileAssociations";

  private static MappingProfileUpdateDto mappingProfile_1 = new MappingProfileUpdateDto()
    .withProfile(new MappingProfile().withName("Bla")
      .withTags(new Tags().withTagList(Arrays.asList("lorem", "ipsum", "dolor")))
      .withIncomingRecordType(EntityType.MARC_BIBLIOGRAPHIC)
      .withExistingRecordType(EntityType.INSTANCE));
  private static MappingProfileUpdateDto mappingProfile_2 = new MappingProfileUpdateDto()
    .withProfile(new MappingProfile().withName("Boo")
      .withTags(new Tags().withTagList(Arrays.asList("lorem", "ipsum")))
      .withIncomingRecordType(EntityType.MARC_BIBLIOGRAPHIC)
      .withExistingRecordType(EntityType.INSTANCE));
  private static MappingProfileUpdateDto mappingProfile_3 = new MappingProfileUpdateDto()
    .withProfile(new MappingProfile().withName("Foo")
      .withTags(new Tags().withTagList(Collections.singletonList("lorem")))
      .withIncomingRecordType(EntityType.MARC_BIBLIOGRAPHIC)
      .withExistingRecordType(EntityType.INSTANCE));

  @Test
  public void shouldReturnEmptyListOnGet() {
    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("totalRecords", is(0))
      .body("mappingProfiles", empty());
  }

  @Test
  public void shouldReturnAllProfilesOnGet() {
    createProfiles();
    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "?withRelations=true")
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("totalRecords", is(3))
      .body("mappingProfiles*.deleted", everyItem(is(false)));
  }

  @Test
  public void shouldReturnCommittedProfilesOnGetWithQueryByLastName() {
    createProfiles();
    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "?query=userInfo.lastName=Doe")
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("totalRecords", is(3))
      .body("mappingProfiles*.userInfo.lastName", everyItem(is("Doe")));
  }

  @Test
  public void shouldReturnIpsumTaggedProfilesOnGetWithQueryByTag() {
    createProfiles();
    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "?query=tags.tagList=/respectCase/respectAccents \\\"ipsum\\\"")
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("totalRecords", is(2))
      .body("mappingProfiles.get(0).tags.tagList", hasItem("ipsum"))
      .body("mappingProfiles.get(1).tags.tagList", hasItem("ipsum"));
  }

  @Test
  public void shouldReturnLimitedCollectionOnGetWithLimit() {
    createProfiles();
    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "?limit=2")
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("mappingProfiles.size()", is(2))
      .body("totalRecords", is(3));
  }

  @Test
  public void shouldReturnBadRequestOnPost() {
    createProfiles();
    RestAssured.given()
      .spec(spec)
      .body(new JsonObject().toString())
      .when()
      .post(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY);
  }

  @Test
  public void shouldCreateProfileOnPost() {
    RestAssured.given()
      .spec(spec)
      .body(mappingProfile_1)
      .when()
      .post(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .body("profile.name", is(mappingProfile_1.getProfile().getName()))
      .body("profile.tags.tagList", is(mappingProfile_1.getProfile().getTags().getTagList()))
      .body("profile.userInfo.lastName", is("Doe"))
      .body("profile.userInfo.firstName", is("Jane"))
      .body("profile.userInfo.userName", is("@janedoe"));

    RestAssured.given()
      .spec(spec)
      .body(mappingProfile_1)
      .when()
      .post(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY);
  }

  @Test
  public void shouldReturnBadRequestOnPut() {
    RestAssured.given()
      .spec(spec)
      .body(new JsonObject().toString())
      .when()
      .put(MAPPING_PROFILES_PATH + "/" + UUID.randomUUID().toString())
      .then()
      .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY);
  }

  @Test
  public void shouldReturnNotFoundOnPut() {
    RestAssured.given()
      .spec(spec)
      .body(mappingProfile_2)
      .when()
      .put(MAPPING_PROFILES_PATH + "/" + UUID.randomUUID().toString())
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void shouldReturnUnprocessableEntityOnPutProfileWithExistingName() {
    createProfiles();

    Response createResponse = RestAssured.given()
      .spec(spec)
      .body(new MappingProfileUpdateDto().withProfile(new MappingProfile()
        .withName("newProfile")
        .withIncomingRecordType(EntityType.MARC_BIBLIOGRAPHIC)
        .withExistingRecordType(EntityType.INSTANCE)))
      .when()
      .post(MAPPING_PROFILES_PATH);
    Assert.assertThat(createResponse.statusCode(), is(HttpStatus.SC_CREATED));
    MappingProfileUpdateDto createdProfile = createResponse.body().as(MappingProfileUpdateDto.class);

    createdProfile.getProfile().setName(mappingProfile_1.getProfile().getName());
    RestAssured.given()
      .spec(spec)
      .body(createdProfile)
      .when()
      .put(MAPPING_PROFILES_PATH + "/" + createdProfile.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY);
  }

  @Test
  public void shouldUpdateProfileOnPut() {
    Response createResponse = RestAssured.given()
      .spec(spec)
      .body(mappingProfile_2)
      .when()
      .post(MAPPING_PROFILES_PATH);
    Assert.assertThat(createResponse.statusCode(), is(HttpStatus.SC_CREATED));
    MappingProfileUpdateDto mappingProfile = createResponse.body().as(MappingProfileUpdateDto.class);

    mappingProfile.getProfile().setDescription("test");
    RestAssured.given()
      .spec(spec)
      .body(mappingProfile)
      .when()
      .put(MAPPING_PROFILES_PATH + "/" + mappingProfile.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("id", is(mappingProfile.getProfile().getId()))
      .body("name", is(mappingProfile.getProfile().getName()))
      .body("description", is("test"))
      .body("tags.tagList", is(mappingProfile.getProfile().getTags().getTagList()))
      .body("userInfo.lastName", is("Doe"))
      .body("userInfo.firstName", is("Jane"))
      .body("userInfo.userName", is("@janedoe"));
  }

  @Test
  public void shouldReturnNotFoundOnGetById() {
    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "/" + UUID.randomUUID().toString())
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void shouldReturnProfileOnGetById() {
    Response createResponse = RestAssured.given()
      .spec(spec)
      .body(mappingProfile_3)
      .when()
      .post(MAPPING_PROFILES_PATH);
    Assert.assertThat(createResponse.statusCode(), is(HttpStatus.SC_CREATED));
    MappingProfileUpdateDto mappingProfile = createResponse.body().as(MappingProfileUpdateDto.class);

    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "/" + mappingProfile.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("id", is(mappingProfile.getProfile().getId()))
      .body("name", is(mappingProfile.getProfile().getName()))
      .body("tags.tagList", is(mappingProfile.getProfile().getTags().getTagList()))
      .body("userInfo.lastName", is("Doe"))
      .body("userInfo.firstName", is("Jane"))
      .body("userInfo.userName", is("@janedoe"));
  }

  @Test
  public void shouldReturnNotFoundOnDelete() {
    RestAssured.given()
      .spec(spec)
      .when()
      .delete(MAPPING_PROFILES_PATH + "/" + UUID.randomUUID().toString())
      .then()
      .statusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void shouldReturnBadRequestOnDeleteProfileAssociatedWithOtherProfiles() {
    Response createResponse = RestAssured.given()
      .spec(spec)
      .body(mappingProfile_1)
      .when()
      .post(MAPPING_PROFILES_PATH);
    Assert.assertThat(createResponse.statusCode(), is(HttpStatus.SC_CREATED));
    MappingProfileUpdateDto profileToDelete = createResponse.body().as(MappingProfileUpdateDto.class);

    createResponse = RestAssured.given()
      .spec(spec)
      .body(new ActionProfileUpdateDto().withProfile(new ActionProfile()
        .withName("testActionProfile")
        .withAction(CREATE)
        .withFolioRecord(MARC_BIBLIOGRAPHIC)))
      .when()
      .post(ACTION_PROFILES_PATH);
    Assert.assertThat(createResponse.statusCode(), is(HttpStatus.SC_CREATED));
    ActionProfileUpdateDto actionProfile = createResponse.body().as(ActionProfileUpdateDto.class);

    RestAssured.given()
      .spec(spec)
      .queryParam("master", ACTION_PROFILE.value())
      .queryParam("detail", MAPPING_PROFILE.value())
      .body(new ProfileAssociation()
        .withMasterProfileId(actionProfile.getProfile().getId())
        .withDetailProfileId(profileToDelete.getProfile().getId())
        .withOrder(1))
      .when()
      .post(ASSOCIATED_PROFILES_PATH)
      .then()
      .statusCode(is(HttpStatus.SC_CREATED));

    RestAssured.given()
      .spec(spec)
      .when()
      .delete(MAPPING_PROFILES_PATH + "/" + profileToDelete.getProfile().getId())
      .then()
      .log().all()
      .statusCode(HttpStatus.SC_CONFLICT);
  }

  @Test
  public void shouldMarkedProfileAsDeletedOnDelete() {
    Response createResponse = RestAssured.given()
      .spec(spec)
      .body(mappingProfile_2)
      .when()
      .post(MAPPING_PROFILES_PATH);
    Assert.assertThat(createResponse.statusCode(), is(HttpStatus.SC_CREATED));
    MappingProfileUpdateDto profile = createResponse.body().as(MappingProfileUpdateDto.class);

    RestAssured.given()
      .spec(spec)
      .when()
      .delete(MAPPING_PROFILES_PATH + "/" + profile.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH + "/" + profile.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("deleted", is(true));
  }

  @Test
  public void shouldReturnMarkedAndUnmarkedAsDeletedProfilesOnGetWhenParameterDeletedIsTrue() {
    createProfiles();
    MappingProfileUpdateDto mappingProfileToDelete = RestAssured.given()
      .spec(spec)
      .body(new MappingProfileUpdateDto().withProfile(new MappingProfile()
        .withName("ProfileToDelete")
        .withIncomingRecordType(EntityType.MARC_BIBLIOGRAPHIC)
        .withExistingRecordType(EntityType.INSTANCE)))
      .when()
      .post(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract().body().as(MappingProfileUpdateDto.class);

    RestAssured.given()
      .spec(spec)
      .when()
      .delete(MAPPING_PROFILES_PATH + "/" + mappingProfileToDelete.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    RestAssured.given()
      .spec(spec)
      .when()
      .param("showDeleted", true)
      .get(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("totalRecords", is(4));
  }

  @Test
  public void shouldReturnOnlyUnmarkedAsDeletedProfilesOnGetWhenParameterDeletedIsNotPassed() {
    createProfiles();
    MappingProfileUpdateDto mappingProfileToDelete = RestAssured.given()
      .spec(spec)
      .body(new MappingProfileUpdateDto().withProfile(new MappingProfile()
        .withName("ProfileToDelete")
        .withIncomingRecordType(EntityType.MARC_BIBLIOGRAPHIC)
        .withExistingRecordType(EntityType.INSTANCE)))
      .when()
      .post(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_CREATED)
      .extract().body().as(MappingProfileUpdateDto.class);

    RestAssured.given()
      .spec(spec)
      .when()
      .delete(MAPPING_PROFILES_PATH + "/" + mappingProfileToDelete.getProfile().getId())
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);

    RestAssured.given()
      .spec(spec)
      .when()
      .get(MAPPING_PROFILES_PATH)
      .then()
      .statusCode(HttpStatus.SC_OK)
      .body("totalRecords", is(3))
      .body("mappingProfiles*.deleted", everyItem(is(false)));
  }

  private void createProfiles() {
    List<MappingProfileUpdateDto> mappingProfilesToPost = Arrays.asList(mappingProfile_1, mappingProfile_2, mappingProfile_3);
    for (MappingProfileUpdateDto profile : mappingProfilesToPost) {
      RestAssured.given()
        .spec(spec)
        .body(profile)
        .when()
        .post(MAPPING_PROFILES_PATH)
        .then()
        .statusCode(HttpStatus.SC_CREATED);
    }
  }

  @Override
  public void clearTables(TestContext context) {
    Async async = context.async();
    PostgresClient pgClient = PostgresClient.getInstance(vertx, TENANT_ID);
    pgClient.delete(ACTION_TO_MAPPING_PROFILES_TABLE, new Criterion(), event ->
      pgClient.delete(MAPPING_PROFILES_TABLE_NAME, new Criterion(), event2 ->
        pgClient.delete(ACTION_PROFILES_TABLE_NAME, new Criterion(), event3 -> {
          if (event3.failed()) {
            context.fail(event3.cause());
          }
          async.complete();
        })));
  }
}
