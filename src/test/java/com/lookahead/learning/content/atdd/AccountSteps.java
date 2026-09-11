package com.lookahead.learning.content.atdd;

import io.cucumber.java.Before;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP security for component scenarios; mocked identity storage, no database or Docker. */
public class AccountSteps {
    private static ConfigurableApplicationContext component;
    private final JsonMapper mapper = new JsonMapper();
    private String base;
    private String password;
    private CookieManager cookies;
    private HttpClient client;
    private HttpClient owner;
    private String token;
    private String ownerToken;
    private String sessionBefore;
    private HttpResponse<String> response;
    private ObjectNode payload;
    private JsonNode saved;
    private JsonNode activityResult;
    private ObjectNode activityBody;
    private String activityKey;
    private String createdPlanId;
    private String expectedAccount;
    private final java.util.List<String> extraPlans = new java.util.ArrayList<>();
    private java.util.List<HttpResponse<String>> concurrent;
    private ObjectNode legacyBody;
    private JsonNode imported;
    private String importKey;
    private record CleanupOwner(HttpClient client,String csrf) {}
    private final java.util.Map<String,CleanupOwner> cleanupOwners=new java.util.LinkedHashMap<>();

    @Before
    public void setup(Scenario scenario) throws Exception {
        if (scenario.getSourceTagNames().contains("@live")) {
            if (!"true".equals(System.getenv("ATDD_LIVE_ENABLED"))) {
                throw new IllegalStateException("Live tests require ATDD_LIVE_ENABLED=true; no services are started by these tests");
            }
            base = System.getenv("ATDD_BASE_URL");
            URI uri = URI.create(base == null ? "" : base);
            if (!"http".equals(uri.getScheme()) || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                    || !(uri.getPort() == 4322 || ("true".equals(System.getenv("ATDD_STANDALONE")) && uri.getPort()>0 && !Set.of(4200,4316,4320,4323).contains(uri.getPort()))) || uri.getUserInfo() != null || !uri.getPath().isEmpty()
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Use isolated4322 or an explicitly authorized standalone synthetic API port");
            }
            String passwordFile = System.getenv("ATDD_PASSWORD_FILE");
            if (passwordFile == null) throw new IllegalStateException("ATDD_PASSWORD_FILE is required");
            password = Files.readString(Path.of(passwordFile)).strip();
            if (password.isEmpty()) throw new IllegalStateException("ATDD_PASSWORD_FILE is empty");
        } else if (scenario.getSourceTagNames().contains("@http")) {
            synchronized (AccountSteps.class) {
                if (component == null) {
                    component = new SpringApplicationBuilder(ComponentApplication.SecurityTestApplication.class)
                            .profiles("accounts", "local-test")
                            .run("--spring.config.name=atdd-component", "--server.port=0", "--server.address=127.0.0.1",
                                    "--app.deployment-environment=local", "--server.servlet.session.cookie.http-only=true",
                                    "--server.servlet.session.cookie.same-site=lax", "--spring.main.banner-mode=off");
                }
            }
            base = "http://127.0.0.1:" + ((WebServerApplicationContext) component).getWebServer().getPort();
            password = "synthetic-test-password";
        }
        newBrowser();
    }

    @AfterAll
    public static void shutdown() {
        if (component != null) { component.close(); component = null; }
    }

    @When("I open a new anonymous browser")
    public void newBrowser() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        token = null;
    }

    private void request(String method, String path, JsonNode body, String key) throws Exception {
        request(method, path, body == null ? null : mapper.writeValueAsString(body), "application/json", key);
    }

    private void request(String method, String path, String body, String type, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + "/api/v1" + path)).timeout(Duration.ofSeconds(15));
        if (expectedAccount != null) builder.header("X-LookAhead-Account", expectedAccount);
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", type);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (method.equals("POST") && (path.equals("/plans") || path.equals("/plans/imports")) && response.statusCode()==201) {
            String id=json().path("data").path("planId").asText();
            if(!id.isBlank()) {if(!extraPlans.contains(id)) extraPlans.add(id);cleanupOwners.putIfAbsent(id,new CleanupOwner(client,token));}
        }
    }

    private JsonNode json() { return mapper.readTree(response.body()); }
    private void csrf() throws Exception {
        request("GET", "/auth/csrf", null, null);
        status(200);
        token = json().path("data").path("token").asText();
        assertThat(token).isNotBlank();
    }
    private String session() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> Set.of("JSESSIONID", "LOOKAHEAD_SESSION").contains(c.getName()))
                .findFirst().orElseThrow().getValue();
    }
    private void login(String username, String suppliedPassword) throws Exception {
        request("POST", "/auth/login", "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(suppliedPassword, StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", null);
    }

    @Given("I am logged in as {string}")
    public void authenticated(String username) throws Exception {
        csrf(); sessionBefore = session(); login(username, password); status(200);
    }
    @When("I log in again as {string}")
    public void loginAgain(String username) throws Exception { newBrowser(); authenticated(username); csrf(); }
    @When("I log in as {string} without a CSRF token")
    public void noCsrf(String username) throws Exception { login(username, password); }
    @When("I log in as {string} with an invalid password")
    public void invalid(String username) throws Exception { csrf(); login(username, "intentionally-invalid"); }
    @When("I request my account")
    public void me() throws Exception { request("GET", "/auth/me", null, null); }
    @When("I log out")
    public void logout() throws Exception { csrf(); request("POST", "/auth/logout", null, null); }
    @Then("the response status is {int}")
    public void status(int expected) {
        assertThat(response.statusCode()).as("HTTP status (response bodies omitted to avoid credential disclosure)").isEqualTo(expected);
    }
    @Then("the error code is {string}")
    public void error(String expected) { assertThat(json().path("code").asText()).isEqualTo(expected); }
    @Then("authentication rotated the browser session")
    public void rotated() { assertThat(session()).isNotEqualTo(sessionBefore); }
    @Then("the response is not cacheable")
    public void noCache() { assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store"); }
    @Then("the response does not contain credentials")
    public void noCredentials() {
        assertThat(response.body().contains(password)).isFalse();
        assertThat(json().path("data").has("password")).isFalse();
    }

    @Given("I have saved a synthetic plan")
    public void savePlan() throws Exception {
        csrf(); owner = client; ownerToken = token;
        try (var input = getClass().getResourceAsStream("/accounts/generated-plan.json")) {
            payload = (ObjectNode) mapper.readTree(input);
        }
        request("POST", "/plans", payload, UUID.randomUUID().toString());
        // Track successful creation before assertions, so later failures still clean up this plan.
        if (response.statusCode() == 201) createdPlanId = json().path("data").path("planId").asText();
        status(201); saved = json().path("data").deepCopy();
        assertThat(saved.path("snapshot")).isEqualTo(payload.path("snapshot"));
    }
    @When("I read the saved plan")
    public void readPlan() throws Exception { request("GET", "/plans/" + createdPlanId, null, null); }
    @Then("the saved snapshot is unchanged")
    public void snapshotUnchanged() { assertThat(json().path("data").path("snapshot")).isEqualTo(saved.path("snapshot")); }
    @Then("the response does not disclose the plan revision")
    public void noRevision() { assertThat(json().has("currentRevision")).isFalse(); }

    private ObjectNode operation(String type) { return mapper.createObjectNode().put("type", type); }
    private String contentId() { return saved.path("snapshot").path("days").get(0).path("assignments").get(0).path("sourceContentId").asText(); }
    private String assignmentId() { return saved.path("snapshot").path("days").get(0).path("assignments").get(0).path("id").asText(); }
    private void activity(ObjectNode operation) throws Exception {
        activityBody = mapper.createObjectNode().put("expectedRevision", saved.path("revision").asInt())
                .put("versionId", saved.path("versionId").asText());
        activityBody.putArray("operations").add(operation);
        activityKey = UUID.randomUUID().toString();
        request("POST", "/plans/" + createdPlanId + "/activity", activityBody, activityKey);
        if (response.statusCode() == 200) activityResult = json().path("data").deepCopy();
    }
    @When("I save a note with the original revision")
    public void note() throws Exception { activity(operation("setNote").put("canonicalContentId", contentId()).put("text", "Synthetic Cucumber note")); }
    @When("I record a needs-review attempt")
    public void attempt() throws Exception {
        activity(operation("recordAttempt").put("assignmentId", assignmentId()).put("canonicalContentId", contentId()).put("outcome", "needs-review"));
    }
    @When("I complete only the planned session")
    public void completeSession() throws Exception { activity(operation("setSessionCompletion").put("assignmentId", assignmentId()).put("completed", true)); }
    @When("I repeat the exact activity request")
    public void retry() throws Exception { request("POST", "/plans/" + createdPlanId + "/activity", activityBody, activityKey); }
    @When("I reuse the activity key with a different note")
    public void changedRetry() throws Exception {
        ((ObjectNode) activityBody.path("operations").get(0)).put("text", "Different note"); retry();
    }
    @Then("the activity response is unchanged")
    public void sameActivity() { assertThat(json().path("data")).isEqualTo(activityResult); }
    @Then("the attempt is recorded separately from completion")
    public void attemptSeparate() {
        JsonNode progress = json().path("data").path("progress");
        assertThat(progress.path("attemptedContentIds").toString()).contains('"' + contentId() + '"');
        assertThat(progress.path("needsReviewContentIds").toString()).contains('"' + contentId() + '"');
        assertThat(progress.path("completedContentIds").size()).isZero();
        assertThat(progress.path("completedSessionIds").size()).isZero();
    }
    @Then("only the session is complete")
    public void onlySession() {
        var progress = json().path("data").path("progress");
        assertThat(progress.path("completedSessionIds").toString()).contains('"' + assignmentId() + '"');
        assertThat(progress.path("completedContentIds").size()).isZero();
    }


    @When("I submit a snapshot with invalid {string}")
    public void tampered(String field) throws Exception {
        ObjectNode body=payload.deepCopy();
        var snapshot=(ObjectNode) body.path("snapshot");
        var day=(ObjectNode) snapshot.path("days").get(0);
        var assignment=(ObjectNode) day.path("assignments").get(0);
        switch(field) {
            case "route" -> assignment.putArray("route").add("https://invalid.example/answer");
            case "canonical" -> assignment.put("sourceContentId","unknown-unpublished-id");
            case "weeks" -> ((ObjectNode) snapshot.path("weeks").get(0).path("days").get(0)).put("focus","Contradiction");
            case "pins" -> ((ObjectNode) body.path("provenance")).put("catalogVersion","sha256:"+"0".repeat(64));
            default -> throw new IllegalArgumentException("Unknown tampering case");
        }
        if (field.equals("route") || field.equals("canonical")) ((tools.jackson.databind.node.ArrayNode) snapshot.path("weeks").get(0).path("days")).set(0,day.deepCopy());
        request("POST","/plans",body,UUID.randomUUID().toString());
        if (response.statusCode()==201) extraPlans.add(json().path("data").path("planId").asText());
    }
    @Given("my expected account header names a different account")
    public void staleAccount() { expectedAccount=UUID.randomUUID().toString(); }
    private ObjectNode recoveryBody() {
        ObjectNode body=payload.deepCopy();body.put("expectedRevision",saved.path("revision").asInt());body.put("reason","recovery");
        body.putObject("recovery").put("strategy","fixed-window").put("elapsedDays",1).put("deadlineDays",saved.path("snapshot").path("config").path("days").asInt()).putArray("deferredContentIds");
        return body;
    }
    @When("I create a recovery version")
    public void recover() throws Exception { request("POST","/plans/"+createdPlanId+"/versions",recoveryBody(),UUID.randomUUID().toString()); }
    @Then("the recovery deadline is unchanged")
    public void deadline() { assertThat(json().path("data").path("recovery").path("deadlineDays")).isEqualTo(saved.path("snapshot").path("config").path("days")); }
    @When("I read the original version")
    public void originalVersion() throws Exception { request("GET","/plans/"+createdPlanId+"/versions/"+saved.path("versionId").asText(),null,null); }
    @When("I submit an implicit deadline extension")
    public void implicitExtension() throws Exception {
        var body=recoveryBody();((ObjectNode)body.path("recovery")).put("deadlineDays",saved.path("snapshot").path("config").path("days").asInt()+1);
        request("POST","/plans/"+createdPlanId+"/versions",body,UUID.randomUUID().toString());
    }
    @When("I submit activity without an expected revision")
    public void missingRevision() throws Exception {
        var body=mapper.createObjectNode().put("versionId",saved.path("versionId").asText());
        body.putArray("operations").add(operation("setNote").put("canonicalContentId",contentId()).put("text","Synthetic note"));
        request("POST","/plans/"+createdPlanId+"/activity",body,UUID.randomUUID().toString());
    }
    private void race(boolean identical) throws Exception {
        HttpClient first=client;String firstToken=token;
        newBrowser();authenticated("learner01");csrf();
        HttpClient second=client;String secondToken=token;
        ObjectNode body=mapper.createObjectNode().put("expectedRevision",saved.path("revision").asInt()).put("versionId",saved.path("versionId").asText());
        body.putArray("operations").add(operation("setNote").put("canonicalContentId",contentId()).put("text","Concurrent first"));
        ObjectNode other=body.deepCopy();if(!identical)((ObjectNode)other.path("operations").get(0)).put("text","Concurrent second");
        String key=UUID.randomUUID().toString();
        var firstRequest=HttpRequest.newBuilder(URI.create(base+"/api/v1/plans/"+createdPlanId+"/activity")).timeout(Duration.ofSeconds(15)).header("X-CSRF-TOKEN",firstToken).header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        var secondRequest=HttpRequest.newBuilder(firstRequest.uri()).timeout(Duration.ofSeconds(15)).header("X-CSRF-TOKEN",secondToken).header("Content-Type","application/json").header("Idempotency-Key",identical?key:UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(other))).build();
        var a=first.sendAsync(firstRequest,HttpResponse.BodyHandlers.ofString());var b=second.sendAsync(secondRequest,HttpResponse.BodyHandlers.ofString());
        concurrent=java.util.List.of(a.join(),b.join());client=first;token=firstToken;
    }
    @When("two independent sessions submit an identical note with one key")
    public void identicalRace() throws Exception {race(true);}
    @When("two independent sessions submit different notes from one revision")
    public void distinctRace() throws Exception {race(false);}
    @Then("both requests return the same saved revision")
    public void sameRace() {assertThat(concurrent).allMatch(r->r.statusCode()==200);assertThat(mapper.readTree(concurrent.get(0).body()).path("data")).isEqualTo(mapper.readTree(concurrent.get(1).body()).path("data"));}
    @Then("exactly one edit succeeds and the other reports a revision conflict")
    public void distinctResult() {
        assertThat(concurrent.stream().filter(r->r.statusCode()==200).count()).isEqualTo(1);
        assertThat(concurrent.stream().filter(r->r.statusCode()==409 && mapper.readTree(r.body()).path("code").asText().equals("REVISION_CONFLICT")).count()).isEqualTo(1);
    }
    @When("I import a legacy browser plan")
    public void importLegacy() throws Exception {
        var local=mapper.createObjectNode().put("schemaVersion","study-plan-local/v1").put("revision",3).put("goal","Synthetic legacy import").putNull("rankingVersion").put("shiftedDays",1);
        local.set("snapshot",payload.path("snapshot").deepCopy());local.putArray("completedIds").add(assignmentId()).add(contentId());
        local.putObject("sessionOutcomes").put(assignmentId(),"needs-review");local.putObject("reviewNotes").put(contentId(),"Preserved imported note");
        local.putArray("attemptedContentIds").add(contentId());local.putArray("needsReviewContentIds").add(contentId());
        local.putArray("history").addObject().put("revision",3).put("changedAt","2026-09-10T00:00:00Z").put("reason","Synthetic legacy recovery");
        legacyBody=mapper.createObjectNode().put("sourceSchemaVersion","study-plan-local/v1");legacyBody.set("localSnapshot",local);
        legacyBody.putObject("provenance").put("snapshotSchemaVersion","study-plan/v1").put("origin","legacy-local-import").put("algorithmVersion","study-schedule/v2").putNull("catalogVersion").putNull("rankingVersion");
        importKey=UUID.randomUUID().toString();repeatLegacy();
        if(response.statusCode()==201) {imported=json().path("data").deepCopy();extraPlans.add(imported.path("planId").asText());}
    }
    @When("I repeat the exact legacy import")
    public void repeatLegacy() throws Exception {request("POST","/plans/imports",legacyBody,importKey);}
    @Then("legacy provenance and notes remain intact")
    public void legacyIntact() {
        var data=json().path("data");assertThat(data.path("provenance").path("catalogVersion").isNull()).isTrue();
        assertThat(data.path("provenance").path("historicalProvenance").asText()).isEqualTo("unknown");
        assertThat(data.path("progress").path("notes").path(contentId()).asText()).isEqualTo("Preserved imported note");
        assertThat(data.path("progress").path("legacySource").path("history")).isEqualTo(legacyBody.path("localSnapshot").path("history"));
    }
    @Then("the legacy import response is unchanged")
    public void importUnchanged() {assertThat(json().path("data")).isEqualTo(imported);}


    private void datedActivity(int day, JsonNode operations) throws Exception {
        activityBody=mapper.createObjectNode().put("expectedRevision",saved.path("revision").asInt()).put("versionId",saved.path("versionId").asText()).put("studyDay",day);
        activityBody.set("operations",operations); activityKey=UUID.randomUUID().toString();
        request("POST","/plans/"+createdPlanId+"/activity",activityBody,activityKey);
        if(response.statusCode()==200) {activityResult=json().path("data").deepCopy();saved=activityResult;}
    }
    @When("I complete the original learning on study day {int}")
    public void datedOriginal(int day) throws Exception {
        var operations=mapper.createArrayNode();
        operations.add(operation("setContentCompletion").put("canonicalContentId",contentId()).put("completed",true));
        operations.add(operation("setSessionCompletion").put("assignmentId",assignmentId()).put("completed",true));
        datedActivity(day,operations);
    }
    @When("I record a daily recall on study day {int}")
    public void datedRecall(int day) throws Exception {
        datedActivity(day,mapper.createArrayNode().add(operation("setSessionCompletion").put("assignmentId",assignmentId()+":daily-recall:"+day).put("completed",true)));
    }
    @Then("the study log contains {int} entries and {int} minutes on day {int}")
    public void studyLog(int count,int minutes,int day) {
        var log=json().path("data").path("progress").path("studyLog");assertThat(log.size()).isEqualTo(count);
        int total=0;for(var entry:log)if(entry.path("day").asInt()==day)total+=entry.path("minutes").asInt();
        assertThat(total).isEqualTo(minutes);
    }
    @Then("the original and daily recall have separate completion identities")
    public void recallIdentity() {
        var progress=json().path("data").path("progress");
        assertThat(progress.path("completedContentIds").size()).isEqualTo(1);
        assertThat(progress.path("completedSessionIds").size()).isEqualTo(2);
    }

    private ObjectNode feedback;
    private JsonNode supportReceipt;
    private static final String supportKey="atdd-support-attachment-v1";
    @When("I submit a synthetic support message with a PNG image")
    public void supportWithImage() throws Exception {
        if(!"true".equals(System.getenv("ATDD_LOCAL_MAIL_CAPTURE"))) throw new IllegalStateException("Mail scenarios require explicit local capture authorization");
        csrf();
        var pixels=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0,0,0x336699);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",bytes);
        feedback=mapper.createObjectNode().put("type","problem").put("subject","Synthetic attachment delivery")
            .put("message","Local capture only: reproduce the synthetic screenshot issue.").put("replyTo","reply@example.test");
        feedback.putArray("images").addObject().put("name","../../untrusted.png").put("data",java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        supportReplay();
        if(response.statusCode()==202) supportReceipt=json().path("data").deepCopy();
    }
    @When("I repeat the identical support submission")
    public void supportReplay() throws Exception { request("POST","/support",feedback,supportKey); }
    @Then("the support receipt is accepted and unchanged")
    public void acceptedReceipt() {
        assertThat(json().path("data")).isEqualTo(supportReceipt);
        assertThat(supportReceipt.path("status").asText()).isEqualTo("accepted");
        assertThat(UUID.fromString(supportReceipt.path("reference").asText())).isNotNull();
    }
    @When("I change the support message with the same request key")
    public void changedSupport() throws Exception { feedback.put("message","Different synthetic text");supportReplay(); }
    @When("I submit a support attachment that is not an image")
    public void invalidSupportImage() throws Exception {
        csrf(); var body=mapper.createObjectNode().put("type","problem").put("subject","Synthetic invalid attachment")
            .put("message","This must not send").put("replyTo","reply@example.test");
        body.putArray("images").addObject().put("name","fake.png").put("data","bm90IGFuIGltYWdl");
        request("POST","/support",body,UUID.randomUUID().toString());
    }

    @When("I request the other account boundary {string}")
    public void otherBoundary(String operation) throws Exception {
        switch(operation) {
            case "history" -> originalVersion();
            case "version" -> recover();
            case "list" -> request("GET","/plans?limit=100",null,null);
            case "delete" -> deleteRequest(saved.path("revision").asInt(),UUID.randomUUID().toString());
            default -> throw new IllegalArgumentException("Unknown boundary");
        }
    }
    @Then("the plan is absent from the account list")
    public void absentFromList() {for(var plan:json().path("data").path("plans"))assertThat(plan.path("planId").asText()).isNotEqualTo(createdPlanId);}
    @When("I submit a guarded {string} request")
    public void guarded(String operation) throws Exception {
        var beforeClient=client;String beforeToken=token;
        expectedAccount=null;request("GET","/plans?limit=100",null,null);status(200);var before=json().path("data").deepCopy();
        staleAccount();
        switch(operation) {
            case "list" -> request("GET","/plans",null,null);
            case "create" -> request("POST","/plans",payload,UUID.randomUUID().toString());
            case "activity" -> note();
            case "import" -> importLegacy();
            default -> throw new IllegalArgumentException("Unknown account guard case");
        }
        status(401);error("ACCOUNT_CHANGED");var failureResponse=response;
        expectedAccount=null;request("GET","/plans?limit=100",null,null);status(200);assertThat(json().path("data")).isEqualTo(before);
        response=failureResponse;
    }
    @When("I submit an invalid plan request {string}")
    public void invalidRequest(String kind) throws Exception {
        switch(kind) {
            case "malformed JSON" -> request("POST","/plans","{broken-json","application/json",UUID.randomUUID().toString());
            case "deep JSON" -> request("POST","/plans","{\"nested\":".repeat(40)+"0"+"}".repeat(40),"application/json",UUID.randomUUID().toString());
            case "missing key" -> request("POST","/plans",payload,null);
            case "forged owner" -> {var body=payload.deepCopy();body.put("userId",UUID.randomUUID().toString());request("POST","/plans",body,UUID.randomUUID().toString());}
            default -> throw new IllegalArgumentException("Unknown invalid request case");
        }
    }
    @When("I exceed the declared body limit for {string}")
    public void oversizedBody(String kind) throws Exception {
        String path=kind.equals("plan")?"/plans":"/plans/"+createdPlanId+"/activity";
        long length=kind.equals("plan")?8*1024*1024+1:256*1024+1;
        var uri=URI.create(base);
        try(var socket=new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(uri.getHost(),uri.getPort()),5000);socket.setSoTimeout(15000);
            String cookie=cookies.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
            String headers="POST /api/v1"+path+" HTTP/1.1\r\nHost: "+uri.getHost()+":"+uri.getPort()+"\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+length+"\r\nCookie: "+cookie+"\r\nX-CSRF-TOKEN: "+token+"\r\nIdempotency-Key: "+UUID.randomUUID()+"\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            String statusLine=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            assertThat(statusLine).startsWith("HTTP/1.1 413");
        }
    }
    @When("I explicitly extend the deadline by one day")
    public void explicitExtension() throws Exception {
        var body=recoveryBody();body.put("reason","extend-deadline");
        var snapshot=(ObjectNode)body.path("snapshot");int days=snapshot.path("config").path("days").asInt()+1;
        ((ObjectNode)snapshot.path("config")).put("days",days);
        var scheduled=(tools.jackson.databind.node.ArrayNode)snapshot.path("days");
        var day=scheduled.addObject().put("day",days).put("phase","Synthetic").put("focus","Explicit deadline extension").put("newCount",0).put("reviewCount",0).put("focusedMinutes",0).put("bufferMinutes",10);day.putArray("assignments");
        var weeks=snapshot.putArray("weeks");for(int i=0;i<scheduled.size();i+=7){var week=weeks.addObject().put("number",i/7+1).put("label","Synthetic week");var entries=week.putArray("days");for(int j=i;j<Math.min(i+7,scheduled.size());j++)entries.add(scheduled.get(j).deepCopy());}
        ((ObjectNode)body.path("recovery")).put("strategy","explicit-extension").put("deadlineDays",days);
        request("POST","/plans/"+createdPlanId+"/versions",body,UUID.randomUUID().toString());
    }
    @Then("the new deadline is one day later and original history is intact")
    public void extensionIntact() throws Exception {
        assertThat(json().path("data").path("recovery").path("deadlineDays").asInt()).isEqualTo(saved.path("snapshot").path("config").path("days").asInt()+1);
        originalVersion();status(200);snapshotUnchanged();
    }
    @When("I reverse only canonical completion")
    public void canonicalUndo() throws Exception {saved=activityResult;activity(operation("setContentCompletion").put("canonicalContentId",contentId()).put("completed",false));}
    private String deletionKey;
    private void deleteRequest(int revision,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+"/api/v1/plans/"+createdPlanId)).timeout(Duration.ofSeconds(15))
            .header("X-CSRF-TOKEN",token).header("Idempotency-Key",key).header("If-Match","\"revision-"+revision+"\"").DELETE();
        response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @When("I delete with a stale revision")
    public void staleDelete() throws Exception {deleteRequest(saved.path("revision").asInt(),UUID.randomUUID().toString());}
    @When("I delete the current plan twice with the same key")
    public void duplicateDelete() throws Exception {
        readPlan();status(200);int revision=json().path("data").path("revision").asInt();deletionKey=UUID.randomUUID().toString();
        deleteRequest(revision,deletionKey);status(204);deleteRequest(revision,deletionKey);
    }
    @When("I delete the imported plan and retry its original import")
    public void deletedImport() throws Exception {deleteOwned(imported.path("planId").asText());repeatLegacy();}
    @When("I verify login rotation and stale CSRF rejection")
    public void staleCsrf() throws Exception {
        newBrowser();csrf();String oldToken=token;String oldSession=session();login("learner01",password);status(200);
        assertThat(session()).isNotEqualTo(oldSession);
        assertThat(response.headers().allValues("set-cookie").toString().toLowerCase()).contains("httponly");
        csrf();String fresh=token;token=oldToken;request("POST","/auth/logout",null,null);status(403);token=fresh;me();status(200);
    }
    @When("I submit ungranted content as the restricted account")
    public void restrictedCreate() throws Exception {loginAgain("learner09");request("POST","/plans",payload,UUID.randomUUID().toString());}
    @Given("I have saved a synthetic persona {int} plan")
    public void personaPlan(int number) throws Exception {
        loginAgain(String.format("learner%02d",number));owner=client;ownerToken=token;
        try(var input=getClass().getResourceAsStream("/accounts/generated-plan.json")){payload=(ObjectNode)mapper.readTree(input);}
        var snapshot=(ObjectNode)payload.path("snapshot");var config=(ObjectNode)snapshot.path("config");
        int count=7+(number-1)%9;int hours=1+(number-1)%3;
        config.put("days",count).put("dailyHours",hours).put("goalType",number%2==1?"interview":"learning");
        String topic=number==9?"learn:hands-on-dsa":"learn:core-java";
        config.putArray("topicIds").add(topic);config.putArray("accessTopicIds").add(topic);
        config.putObject("familiarity").put(topic,new String[]{"new","refresh","familiar"}[(number-1)%3]);
        snapshot.put("focusedDailyHours",hours-.25);
        var first=snapshot.path("days").get(0).deepCopy();var assignment=(ObjectNode)first.path("assignments").get(0);
        if(number==9){assignment.put("id","smoke-session-fixture-b").put("sourceContentId","fixture-b").put("topicId",topic).put("activity","Practice").put("contentType","dsa-problem");assignment.putArray("route").add("/").add("learn").add("hands-on-dsa").add("fixture-b");((ObjectNode)snapshot.path("includedTopics").get(0)).put("id",topic);}
        var days=snapshot.putArray("days");days.add(first);
        for(int i=2;i<=count;i++){var day=days.addObject().put("day",i).put("phase","Synthetic").put("focus","Verify account persistence").put("newCount",0).put("reviewCount",0).put("focusedMinutes",0).put("bufferMinutes",10);day.putArray("assignments");}
        var weeks=snapshot.putArray("weeks");for(int i=0;i<days.size();i+=7){var entries=weeks.addObject().put("number",i/7+1).put("label","Synthetic week").putArray("days");for(int j=i;j<Math.min(i+7,days.size());j++)entries.add(days.get(j).deepCopy());}
        request("POST","/plans",payload,UUID.randomUUID().toString());if(response.statusCode()==201)createdPlanId=json().path("data").path("planId").asText();status(201);saved=json().path("data").deepCopy();
    }
    @When("I record an independent persona note and attempt")
    public void personaProgress() throws Exception {
        activityBody=mapper.createObjectNode().put("expectedRevision",saved.path("revision").asInt()).put("versionId",saved.path("versionId").asText());
        var operations=activityBody.putArray("operations");operations.add(operation("recordAttempt").put("assignmentId",assignmentId()).put("canonicalContentId",contentId()).put("outcome","needs-review"));
        operations.add(operation("setNote").put("canonicalContentId",contentId()).put("text","Synthetic persona note "+createdPlanId));
        request("POST","/plans/"+createdPlanId+"/activity",activityBody,UUID.randomUUID().toString());status(200);activityResult=json().path("data").deepCopy();
    }
    @Then("the complete saved state matches the last activity")
    public void exactRestoration(){assertThat(json().path("data")).isEqualTo(activityResult);}

    @When("I include known inaccessible prerequisite metadata")
    public void blockedMetadata() throws Exception {
        me();status(200);var grants=json().path("data").path("topicGrants").deepCopy();
        var body=payload.deepCopy();((ObjectNode)body.path("snapshot")).putArray("blockedItems").addObject().put("id","fixture-b").put("title","Accessible item awaiting a prerequisite").putArray("prerequisiteIds").add("fixture-a");
        request("POST","/plans",body,UUID.randomUUID().toString());status(201);var result=response;var blockedPlan=json().path("data");
        assertThat(blockedPlan.path("snapshot")).isEqualTo(body.path("snapshot"));
        var activity=mapper.createObjectNode().put("expectedRevision",blockedPlan.path("revision").asInt()).put("versionId",blockedPlan.path("versionId").asText());
        activity.putArray("operations").add(operation("setNote").put("canonicalContentId","fixture-a").put("text","Must not create inaccessible progress"));
        request("POST","/plans/"+blockedPlan.path("planId").asText()+"/activity",activity,UUID.randomUUID().toString());status(422);
        me();status(200);assertThat(json().path("data").path("topicGrants")).isEqualTo(grants);response=result;
    }
    @When("I submit invalid restricted metadata {string}")
    public void invalidMetadata(String kind) throws Exception {
        var body=payload.deepCopy();var snapshot=(ObjectNode)body.path("snapshot");
        var blocked=snapshot.putArray("blockedItems").addObject().put("id","fixture-b").put("title","Synthetic blocked item");blocked.putArray("prerequisiteIds").add("fixture-a");
        switch(kind) {
            case "unknown prerequisite" -> blocked.putArray("prerequisiteIds").add("unpublished-content");
            case "inaccessible item" -> blocked.put("id","fixture-a");
            case "future review" -> {
                ((tools.jackson.databind.node.ArrayNode)snapshot.path("config").path("topicIds")).add("learn:core-java");
                var review=snapshot.putArray("futureReviews").addObject().put("id","fixture-a:review:9").put("sourceContentId","fixture-a").put("kind","review").put("activity","Recall").put("topicId","learn:core-java").put("title","Unpermitted review").put("minutes",10).put("reviewDueDay",9).put("contentType","theory");review.putArray("route").add("/").add("learn").add("core-java").add("fixture-a");
            }
            default -> throw new IllegalArgumentException("Unknown metadata case");
        }
        request("POST","/plans",body,UUID.randomUUID().toString());
    }
    @When("another account saves the identical schedule")
    public void equalAccountPlans() throws Exception {
        me();status(200);String firstAccount=json().path("data").path("accountId").asText();
        loginAgain("learner02");me();status(200);assertThat(json().path("data").path("accountId").asText()).isNotEqualTo(firstAccount);
        request("POST","/plans",payload,UUID.randomUUID().toString());status(201);var other=json().path("data");
        assertThat(other.path("planId")).isNotEqualTo(saved.path("planId"));assertThat(other.path("snapshot")).isEqualTo(saved.path("snapshot"));
        client=owner;token=ownerToken;note();status(200);
        loginAgain("learner02");request("GET","/plans/"+other.path("planId").asText(),null,null);status(200);
        assertThat(json().path("data").path("progress").path("notes").size()).isZero();
    }

    @After("@live")
    public void cleanup() throws Exception {
        var failures = new java.util.ArrayList<Throwable>();expectedAccount=null;
        for(var entry:cleanupOwners.entrySet()) {
            client=entry.getValue().client();token=entry.getValue().csrf();
            try {deleteOwned(entry.getKey());}catch(Exception|AssertionError failure){failures.add(failure);}
        }
        if(!failures.isEmpty()) {var failure=new AssertionError("Synthetic cleanup failed for "+failures.size()+" plan(s)");failures.forEach(failure::addSuppressed);throw failure;}
    }
    private void deleteOwned(String planId) throws Exception {
        request("GET", "/plans/" + planId, null, null); if(response.statusCode()==404) return; status(200);
        int revision = json().path("data").path("revision").asInt();
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/plans/" + planId))
                .timeout(Duration.ofSeconds(15)).header("X-CSRF-TOKEN", token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"revision-" + revision + "\"")
                .DELETE().build();
        response = client.send(request, HttpResponse.BodyHandlers.ofString()); status(204);
    }
}
