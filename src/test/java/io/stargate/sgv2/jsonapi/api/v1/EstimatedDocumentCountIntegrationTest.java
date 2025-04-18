package io.stargate.sgv2.jsonapi.api.v1;

import static io.restassured.RestAssured.given;
import static io.stargate.sgv2.jsonapi.api.v1.ResponseAssertions.responseIsStatusOnly;
import static io.stargate.sgv2.jsonapi.api.v1.ResponseAssertions.responseIsWriteSuccess;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import io.stargate.sgv2.jsonapi.testresource.DseTestResource;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusIntegrationTest
@WithTestResource(value = DseTestResource.class, restrictToAnnotatedClass = false)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@Disabled("Disabled for CI, requires a test configuration where system.size_estimates is enabled")
public class EstimatedDocumentCountIntegrationTest extends AbstractCollectionIntegrationTestBase {

  private static final Logger LOG =
      LoggerFactory.getLogger(EstimatedDocumentCountIntegrationTest.class);

  public static final int MAX_ITERATIONS = 100;

  @Nested
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  @Order(1)
  class Count {

    public static final int TIME_TO_SETTLE = 75;

    public static final String JSON_ESTIMATED_COUNT =
        """
          {
            "estimatedDocumentCount": {
            }
          }
          """;
    public static final String JSON_ACTUAL_COUNT =
        """
          {
             "countDocuments": {
             }
          }
          """;
    public static final String INSERT_MANY =
        """
            {
              "insertMany": {
                "documents": [
                  {
                      "username": "user1",
                      "subdoc" : {
                         "id" : "12345"
                      },
                      "array" : [
                          "value1"
                      ]
                  },
                  {
                      "username": "user2",
                      "subdoc" : {
                         "id" : "abc"
                      },
                      "array" : [
                          "value2"
                      ]
                  },
                  {
                      "username": "user3",
                      "tags" : ["tag1", "tag2", "tag1234567890123456789012345", null, 1, true],
                      "nestedArray" : [["tag1", "tag2"], ["tag1234567890123456789012345", null]]
                  },
                  {
                      "username": "user4",
                      "indexedObject" : { "0": "value_0", "1": "value_1" }
                  },
                  {
                      "username": "user5",
                      "sub_doc" : { "a": 5, "b": { "c": "v1", "d": false } }
                  }
                ],
                "options" : {
                  "ordered" : true
                }
              }
            }
            """;

    @Test
    @Order(1)
    public void insertDocuments() throws InterruptedException {

      // execute the insertMany command in a loop until the response indicates a non-zero count, or
      // we have executed the command 100 times
      int tries = 1;
      while (tries <= MAX_ITERATIONS) {
        insertMany();

        // get count results every N iterations
        if (tries % 500 == 0) {

          int estimatedCount = getEstimatedCount();
          int actualCount = getActualCount();

          LOG.info(
              "Iteration: "
                  + tries
                  + ", Docs inserted: "
                  + tries * 5
                  + ", Actual count: "
                  + actualCount
                  + ", Estimated count: "
                  + estimatedCount);

          if (estimatedCount > 0) {
            break;
          }
        }

        tries++;
      }

      LOG.info(
          "Stopping insertion after non-zero estimated count, now waiting "
              + TIME_TO_SETTLE
              + " seconds for count to settle");
      Thread.sleep(TIME_TO_SETTLE * 1000);
      LOG.info("Final estimated count: " + getEstimatedCount());
    }

    /**
     * Verify that truncating the collection (DeleteMany with no filter) yields a zero estimated
     * document count.
     */
    @Test
    @Order(2)
    public void truncate() throws InterruptedException {

      String jsonTruncate =
          """
              {
                "deleteMany": {
                }
              }
              """;
      given()
          .headers(getHeaders())
          .contentType(ContentType.JSON)
          .body(jsonTruncate)
          .when()
          .post(CollectionResource.BASE_PATH, keyspaceName, collectionName)
          .then()
          .statusCode(200)
          .body("$", responseIsStatusOnly())
          .body("status.deletedCount", is(-1))
          .body("status.moreData", is(nullValue()));

      LOG.info(
          "Truncated collection, waiting for estimated count to settle for "
              + TIME_TO_SETTLE
              + " seconds");
      Thread.sleep(TIME_TO_SETTLE * 1000);
      LOG.info("Final estimated count after truncate: " + getEstimatedCount());

      // ensure estimated doc count is zero
      // does not find the documents
      given()
          .headers(getHeaders())
          .contentType(ContentType.JSON)
          .body(JSON_ESTIMATED_COUNT)
          .when()
          .post(CollectionResource.BASE_PATH, keyspaceName, collectionName)
          .then()
          .statusCode(200)
          .body("$", responseIsStatusOnly())
          .body("status.count", is(0));
    }

    private int getActualCount() {
      return given()
          .headers(getHeaders())
          .contentType(ContentType.JSON)
          .body(JSON_ACTUAL_COUNT)
          .when()
          .post(CollectionResource.BASE_PATH, keyspaceName, collectionName)
          .then()
          .statusCode(200)
          .body("$", responseIsStatusOnly())
          .extract()
          .response()
          .jsonPath()
          .getInt("status.count");
    }

    private int getEstimatedCount() {
      return given()
          .headers(getHeaders())
          .contentType(ContentType.JSON)
          .body(JSON_ESTIMATED_COUNT)
          .when()
          .post(CollectionResource.BASE_PATH, keyspaceName, collectionName)
          .then()
          .statusCode(200)
          .body("$", responseIsStatusOnly())
          .extract()
          .response()
          .jsonPath()
          .getInt("status.count");
    }

    private void insertMany() {
      given()
          .headers(getHeaders())
          .contentType(ContentType.JSON)
          .body(INSERT_MANY)
          .when()
          .post(CollectionResource.BASE_PATH, keyspaceName, collectionName)
          .then()
          .statusCode(200)
          .body("$", responseIsWriteSuccess());
    }
  }

  @Nested
  @Order(2)
  class Metrics {
    @Test
    public void checkMetrics() {
      EstimatedDocumentCountIntegrationTest.super.checkMetrics("EstimatedDocumentCountCommand");
    }
  }
}
