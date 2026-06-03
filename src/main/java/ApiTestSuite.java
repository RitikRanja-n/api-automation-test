import com.aventstack.extentreports.*;
import com.aventstack.extentreports.reporter.*;
import com.aventstack.extentreports.reporter.configuration.Theme;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.*;
import io.restassured.http.ContentType;
import io.restassured.module.jsv.JsonSchemaValidator;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.logging.log4j.*;
import org.testng.*;
import org.testng.annotations.*;
import org.testng.xml.*;
import java.io.File;
import java.lang.reflect.Method;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

public class ApiTestSuite {

    // =========================================================================
    // CONSTANTS (given)
    // =========================================================================
    private static final String BASE_URL      = "https://reqres.in/api";
    private static final String LOGIN_EMAIL   = "eve.holt@reqres.in";
    private static final String LOGIN_PASS    = "cityslicker";
    private static final String REPORTS_DIR   = "test-reports";
    private static final String API_KEY       = "free_user_3EciEOynoEW4CVaz98LcGBakMFo";   // reqres.in free-tier key

    // =========================================================================
    // JSON SCHEMA (given – use in TC002)
    // =========================================================================
    private static final String USER_SCHEMA =
            "{\"$type\":\"object\",\"required\":[\"data\"]," +
            "\"properties\":{\"data\":{\"$type\":\"object\"," +
            "\"required\":[\"id\",\"email\",\"first_name\",\"last_name\"]," +
            "\"properties\":{" +
            "\"id\":{\"$type\":\"integer\"}," +
            "\"email\":{\"$type\":\"string\"}," +
            "\"first_name\":{\"$type\":\"string\"}," +
            "\"last_name\":{\"$type\":\"string\"} }}}}";

    // =========================================================================
    // LOGGING (given)
    // =========================================================================
    private static final Logger log = LogManager.getLogger(ApiTestSuite.class);

    // =========================================================================
    // EXTENT REPORTS (given)
    // =========================================================================
    private static ExtentReports extent = null;
    private static final ThreadLocal<ExtentTest> extentTest = new ThreadLocal<>();

    // =========================================================================
    // SHARED AUTH TOKEN (set in TC007, used in same method)
    // =========================================================================
    private static String authToken = null;

    // =========================================================================
    // REQUEST SPEC (set in @BeforeClass, use in all tests)
    // =========================================================================
    private static RequestSpecification requestSpec;

    // =========================================================================
    // PRE-WRITTEN POJOs – UserData, UserSingleResponse, UserListResponse (given)
    // =========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class UserData {
        @JsonProperty("id")         public int    id;
        @JsonProperty("email")      public String email;
        @JsonProperty("first_name") public String firstName;
        @JsonProperty("last_name")  public String lastName;
        @JsonProperty("avatar")     public String avatar;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class UserSingleResponse {
        @JsonProperty("data") public UserData data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class UserListResponse {
        @JsonProperty("page")        public int           page;
        @JsonProperty("per_page")    public int           perPage;
        @JsonProperty("total")       public int           total;
        @JsonProperty("total_pages") public int           totalPages;
        @JsonProperty("data")        public List<UserData> data;
    }

    // =========================================================================
    // TODO 1 – POJO Classes
    // =========================================================================

    // TODO 1a: CreateUserRequest
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CreateUserRequest {
        @JsonProperty("name") public String name;
        @JsonProperty("job")  public String job;
    }

    // TODO 1b: CreateUserResponse
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CreateUserResponse {
        @JsonProperty("name")      public String name;
        @JsonProperty("job")       public String job;
        @JsonProperty("id")        public String id;          // id is String in response
        @JsonProperty("createdAt") public String createdAt;
    }

    // TODO 1c: LoginRequest
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LoginRequest {
        @JsonProperty("email")    public String email;
        @JsonProperty("password") public String password;
    }

    // TODO 1d: LoginResponse
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LoginResponse {
        @JsonProperty("token") public String token;
    }

    // =========================================================================
    // EXTENT REPORT – GIVEN (do not modify)
    // =========================================================================

    @BeforeSuite(alwaysRun = true)
    public void initReporting() {
        new File(REPORTS_DIR).mkdirs();
        String ts   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String path = REPORTS_DIR + "/ApiResponse_" + ts + ".html";
        ExtentSparkReporter spark = new ExtentSparkReporter(path);
        spark.config().setDocumentTitle("REST Assured API Report");
        spark.config().setReportName("Reqres.in - API Test Suite");
        spark.config().setTheme(Theme.DARK);
        extent = new ExtentReports();
        extent.attachReporter(spark);
        extent.setSystemInfo("Base URL",   BASE_URL);
        extent.setSystemInfo("Framework", "REST Assured + TestNG");
        log.info("ExtentReports ready -> {}", path);
    }

    @AfterSuite(alwaysRun = true)
    public void flushReporting() {
        if (extent != null) extent.flush();
        log.info("ExtentReports flushed.");
    }

    @BeforeMethod(alwaysRun = true)
    public void setupTest(Method method) {
        Test ann  = method.getAnnotation(Test.class);
        String desc = (ann != null && !ann.description().isEmpty()) ? ann.description() : method.getName();
        ExtentTest test = extent.createTest(desc);
        extentTest.set(test);
        log.info("Started Test: {}", method.getName());
    }

    @AfterMethod(alwaysRun = true)
    public void reportResult(ITestResult result) {
        if (result.getStatus() == ITestResult.FAILURE) {
            log.error("X FAILED: {}", result.getName());
            extentTest.get().fail(result.getThrowable());
        } else if (result.getStatus() == ITestResult.SKIP) {
            log.warn("! SKIPPED: {}", result.getName());
            extentTest.get().skip("Skipped");
        } else {
            log.info("PASSED: {}", result.getName());
            extentTest.get().pass("Test Passed");
        }
    }

    private void step(String msg) {
        log.info(" STEP: {}", msg);
        extentTest.get().info(msg);
    }

    // =========================================================================
    // TODO 2 — @BeforeClass: Configure REST Assured
    // =========================================================================

    @BeforeClass
    public void configureRestAssured() {
        requestSpec = new RequestSpecBuilder()
                .setBaseUri(BASE_URL)
                .setContentType(ContentType.JSON)
                .addHeader("x-api-key", API_KEY)
                .addFilter(new RequestLoggingFilter())
                .addFilter(new ResponseLoggingFilter())
                .build();
        log.info("RequestSpec configured -> {}", BASE_URL);
    }

    // =========================================================================
    // TODO 3 — @DataProvider
    // =========================================================================

    @DataProvider(name = "userCreationData")
    public Object[][] userCreationData() {
        return new Object[][] {
            { "morpheus", "leader"   },
            { "neo",      "the one"  },
            { "trinity",  "operator" }
        };
    }

    // =========================================================================
    // TODO 4 — TEST CASES
    // =========================================================================

    // TODO 4a: TC001 — GET Users List
    @Test(
        groups       = {"smoke", "get"},
        description  = "TC001: GET /users - status 200, verify total, data size, response time",
        priority     = 1
    )
    public void TC001_GetUserList() {
        step("Sending GET /users?page=1");
        given(requestSpec)
            .queryParam("page", 1)
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("total",       equalTo(12))
            .body("total_pages", equalTo(2))
            .body("data",        hasSize(6))
            .body("data[0].id",  notNullValue())
            .time(lessThan(3000L));
        step("TC001 passed — status 200, total=12, total_pages=2, data.size=6, response<3s");
    }

    // TODO 4b: TC002 — GET Single User (Jackson deserialization + JSON schema)
    @Test(
        groups      = {"smoke", "get"},
        description = "TC002: GET /users/2 - Jackson POJO + schema validation",
        priority    = 2
    )
    public void TC002_GetSingleUser() {
        step("Sending GET /users/2 with schema validation and POJO deserialization");
        UserSingleResponse resp = given(requestSpec)
            .when()
                .get("/users/2")
            .then()
                .statusCode(200)
                .body(JsonSchemaValidator.matchesJsonSchema(USER_SCHEMA))
                .extract()
                .as(UserSingleResponse.class);

        Assert.assertNotNull(resp.data,           "UserData should not be null");
        Assert.assertEquals(resp.data.email,      "janet.weaver@reqres.in", "Email mismatch");
        Assert.assertEquals(resp.data.firstName,  "Janet",                  "First name mismatch");
        Assert.assertEquals(resp.data.id,         2,                        "User ID mismatch");
        step("TC002 passed — user id=2, email=janet.weaver@reqres.in, firstName=Janet");
    }

    // TODO 4c: TC003 — User Not Found (Negative)
    @Test(
        groups      = {"smoke", "negative"},
        description = "TC003: GET /users/23 - expect 404",
        priority    = 3
    )
    public void TC003_UserNotFound() {
        step("Sending GET /users/23 — expecting 404");
        given(requestSpec)
            .when()
                .get("/users/23")
            .then()
                .statusCode(404);
        step("TC003 passed — status 404 confirmed for non-existent user");
    }

    // TODO 4d: TC004 — POST Create User
    @Test(
        groups      = {"smoke", "post"},
        description = "TC004: POST /users - 201, verify name/job/id/createdAt",
        priority    = 4
    )
    public void TC004_CreateUser() {
        step("Building CreateUserRequest — name=morpheus, job=leader");
        CreateUserRequest req = new CreateUserRequest();
        req.name = "morpheus";
        req.job  = "leader";

        // TIP: manual ObjectMapper approach (commented for reference):
        // String json = given(requestSpec).body(req).when().post("/users").asString();
        // CreateUserResponse resp = new ObjectMapper().readValue(json, CreateUserResponse.class);

        step("Sending POST /users");
        CreateUserResponse resp = given(requestSpec)
            .body(req)
        .when()
            .post("/users")
        .then()
            .statusCode(201)
            .extract()
            .as(CreateUserResponse.class);

        Assert.assertEquals(resp.name, "morpheus",  "Name mismatch");
        Assert.assertEquals(resp.job,  "leader",    "Job mismatch");
        Assert.assertNotNull(resp.id,               "id should not be null");
        Assert.assertNotNull(resp.createdAt,         "createdAt should not be null");
        step("TC004 passed — user created, id=" + resp.id + ", createdAt=" + resp.createdAt);
    }

    // TODO 4e: TC005 — PUT Update User
    @Test(
        groups      = {"regression", "put"},
        description = "TC005: PUT /users/2 - 200, verify name/job/updatedAt",
        priority    = 5
    )
    public void TC005_UpdateUser() {
        step("Building CreateUserRequest for update — name=morpheus, job=zion resident");
        CreateUserRequest req = new CreateUserRequest();
        req.name = "morpheus";
        req.job  = "zion resident";

        step("Sending PUT /users/2");
        given(requestSpec)
            .body(req)
        .when()
            .put("/users/2")
        .then()
            .statusCode(200)
            .body("name",      equalTo("morpheus"))
            .body("job",       equalTo("zion resident"))
            .body("updatedAt", notNullValue());
        step("TC005 passed — user updated, name=morpheus, job=zion resident, updatedAt present");
    }

    // TODO 4f: TC006 — DELETE User
    @Test(
        groups      = {"regression", "delete"},
        description = "TC006: DELETE /users/2 - expect 204 No Content",
        priority    = 6
    )
    public void TC006_DeleteUser() {
        step("Sending DELETE /users/2");
        given(requestSpec)
            .when()
                .delete("/users/2")
            .then()
                .statusCode(204);
        step("TC006 passed — status 204 confirmed");
    }

    // TODO 4g: TC007 — Login + Bearer Token (Request Chaining)
    @Test(
        groups      = {"regression", "auth"},
        description = "TC007: POST /login, extract token, chain as Bearer in GET /users",
        priority    = 7
    )
    public void TC007_LoginAndBearerToken() {
        // STEP 1 – Login
        step("STEP 1: Building LoginRequest and sending POST /login");
        LoginRequest loginReq = new LoginRequest();
        loginReq.email    = LOGIN_EMAIL;
        loginReq.password = LOGIN_PASS;

        LoginResponse loginResp = given(requestSpec)
            .body(loginReq)
        .when()
            .post("/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .extract()
            .as(LoginResponse.class);

        authToken = loginResp.token;
        Assert.assertNotNull(authToken, "Auth token must not be null");
        step("STEP 1 passed — token extracted: " + authToken);

        // STEP 2 – Chained request using token
        step("STEP 2: Sending GET /users with Authorization: Bearer token");
        given(requestSpec)
            .header("Authorization", "Bearer " + authToken)
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("data", hasSize(greaterThan(0)));
        step("TC007 passed — login success, token not null, chained GET /users works");
    }

    // TODO 4h: TC008 — Data-Driven POST via @DataProvider
    @Test(
        groups       = {"regression", "data-driven"},
        description  = "TC008: Data-driven POST /users - 3 users from @DataProvider",
        dataProvider = "userCreationData",
        priority     = 8
    )
    public void TC008_DataDrivenCreate(String name, String job) {
        step("Creating user: name=" + name + ", job=" + job);

        CreateUserRequest req = new CreateUserRequest();
        req.name = name;
        req.job  = job;

        given(requestSpec)
            .body(req)
        .when()
            .post("/users")
        .then()
            .statusCode(201)
            .body("name", equalTo(name))
            .body("job",  equalTo(job))
            .body("id",   notNullValue());

        step("TC008 passed for name=" + name + ", job=" + job);
    }

    // =========================================================================
    // TODO 5 — main(): Programmatic TestNG Run
    // =========================================================================

    public static void main(String[] args) {
        // 1. Create XmlSuite
        XmlSuite xmlSuite = new XmlSuite();
        xmlSuite.setName("Reqres API Suite");
        xmlSuite.setParallel(XmlSuite.ParallelMode.NONE);

        // 2. Create XmlTest and add ApiTestSuite class
        XmlTest xmlTest = new XmlTest(xmlSuite);
        xmlTest.setName("Full API Regression");
        xmlTest.setXmlClasses(Collections.singletonList(new XmlClass(ApiTestSuite.class)));

        // Comment showing smoke-only run:
        // xmlTest.setIncludedGroups(Collections.singletonList("smoke"));

        // 3. Run via TestNG
        TestNG runner = new TestNG();
        runner.setXmlSuites(Collections.singletonList(xmlSuite));
        runner.run();

        // 4. Print report directory path
        System.out.println("Reports -> test-reports/");
    }
}