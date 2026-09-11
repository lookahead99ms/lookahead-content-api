package com.lookahead.learning.content.atdd;

import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

/** HTTP-only lifecycle probes. Infrastructure owns every container and database operation. */
public class AccountProbeSteps {
    private final JsonMapper mapper=new JsonMapper();
    private final List<String> checks=new ArrayList<>();
    private URI base;
    private String password;
    private ObjectNode catalog;
    private Path statePath,cookiePath,outputPath;
    private void check(boolean condition,String label) {if(!condition)throw new AssertionError(label);checks.add(label);}
    private String required(String name) {String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
    private Path privatePath(String value) throws Exception {
        Path path=Path.of(value).toAbsolutePath().normalize(),scratch=Path.of(".codex-scratch").toAbsolutePath().normalize();
        if(!path.startsWith(scratch))throw new IllegalArgumentException("Probe state must stay in API scratch");
        for(Path current=path;current!=null && current.startsWith(scratch);current=current.getParent())if(Files.isSymbolicLink(current))throw new IllegalArgumentException("Probe state cannot follow symlinks");
        Files.createDirectories(path.getParent());return path;
    }
    private void writePrivate(Path path,JsonNode value) throws Exception {
        Path stage=Files.createTempFile(path.getParent(),"probe-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{Files.writeString(stage,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));Files.move(stage,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(stage);}
    }
    @When("the {string} account lifecycle probe runs")
    public void probe(String phase) throws Exception {
        if(!phase.equals(required("ATDD_PROBE_PHASE")))throw new IllegalStateException("Probe phase differs from selected feature");
        base=URI.create(required("ATDD_PROBE_BASE_URL"));
        boolean restore=phase.equals("restore");
        check("http".equals(base.getScheme()) && Set.of("127.0.0.1","localhost").contains(base.getHost()) &&
            (restore?base.getPort()==4323:Set.of(4320,4322).contains(base.getPort())) && base.getUserInfo()==null && base.getPath().isEmpty() && base.getQuery()==null && base.getFragment()==null,"Loopback lifecycle boundary");
        password=Files.readString(Path.of(required("ATDD_PASSWORD_FILE"))).strip();check(!password.isBlank(),"Local credential file exists");
        outputPath=privatePath(required("ATDD_PROBE_OUTPUT"));
        try {
            if(restore)restoreAccounts();
            else {
                statePath=privatePath(required("ATDD_PROBE_STATE"));
                String filename=statePath.getFileName().toString();int dot=filename.lastIndexOf('.');
                cookiePath=privatePath(statePath.resolveSibling((dot<0?filename:filename.substring(0,dot))+".cookies").toString());
                catalog=(ObjectNode)mapper.readTree(Files.readString(Path.of(required("ATDD_PROBE_CATALOG"))));
                lifecycle(phase);
            }
            var report=mapper.createObjectNode().put("result","passed").put("phase",phase).put("checksPassed",checks.size());
            var list=report.putArray("checks");checks.forEach(list::add);report.putArray("limitations").add("HTTP-only Java/Cucumber probe; infrastructure lifecycle is separately orchestrated").add("Synthetic snapshots do not certify frontend scheduling or production identity");writePrivate(outputPath,report);
        } catch(Exception|AssertionError failure) {
            writePrivate(outputPath,mapper.createObjectNode().put("result","failed").put("phase",phase).put("checksPassed",checks.size()).put("failure","Account lifecycle contract failed; inspect the private verification log"));throw failure;
        }
    }
    private final class Client {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        final String username;
        ObjectNode csrf;
        HttpResponse<String> response;
        Client(String username){this.username=username;}
        JsonNode request(String method,String path,JsonNode body,String key,int expected) throws Exception {return request(method,path,body,key,expected,Map.of());}
        JsonNode request(String method,String path,JsonNode body,String key,int expected,Map<String,String> headers) throws Exception {
            var request=HttpRequest.newBuilder(base.resolve("/api/v1"+path)).timeout(Duration.ofSeconds(20));
            if(csrf!=null && !method.equals("GET"))request.header(csrf.path("headerName").asText(),csrf.path("token").asText());
            if(key!=null)request.header("Idempotency-Key",key);headers.forEach(request::header);
            if(body!=null)request.header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());check(response.statusCode()==expected,"HTTP "+method+" "+path.replaceAll("[a-f0-9-]{36}","{owned-plan}")+" returns "+expected);
            return response.body().isBlank()?mapper.nullNode():mapper.readTree(response.body());
        }
        ObjectNode login() throws Exception {
            token();var before=cookies.getCookieStore().getCookies().stream().map(HttpCookie::toString).toList();
            var request=HttpRequest.newBuilder(base.resolve("/api/v1/auth/login")).timeout(Duration.ofSeconds(20))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8)));
            response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());check(response.statusCode()==200,"Synthetic login succeeds");
            check(response.headers().allValues("set-cookie").toString().toLowerCase().contains("httponly"),"Session cookie is HttpOnly");
            check(!before.equals(cookies.getCookieStore().getCookies().stream().map(HttpCookie::toString).toList()),"Login rotates session");
            var identity=(ObjectNode)mapper.readTree(response.body()).path("data");token();return identity;
        }
        void token() throws Exception {csrf=(ObjectNode)request("GET","/auth/csrf",null,null,200).path("data");}
        void loadCookies() throws Exception {
            if(!Files.exists(cookiePath))return;
            String raw=Files.readString(cookiePath);
            if(raw.startsWith("#LWP-Cookies")) {
                for(String line:raw.lines().toList())if(line.startsWith("Set-Cookie3:"))for(var cookie:HttpCookie.parse(line.replaceFirst("Set-Cookie3:","Set-Cookie:"))){cookie.setVersion(0);cookies.getCookieStore().add(base,cookie);}
            } else for(var entry:mapper.readTree(raw)){
                var cookie=new HttpCookie(entry.path("name").asText(),entry.path("value").asText());cookie.setPath(entry.path("path").asText("/"));cookie.setVersion(0);cookie.setSecure(entry.path("secure").asBoolean());cookies.getCookieStore().add(base,cookie);
            }
        }
        void saveCookies() throws Exception {
            var list=mapper.createArrayNode();for(var cookie:cookies.getCookieStore().getCookies())list.addObject().put("name",cookie.getName()).put("value",cookie.getValue()).put("path",cookie.getPath()==null?"/":cookie.getPath()).put("secure",cookie.getSecure());writePrivate(cookiePath,list);
        }
        void delete(JsonNode plan) throws Exception {request("DELETE","/plans/"+plan.path("planId").asText(),null,UUID.randomUUID().toString(),204,Map.of("If-Match","\"revision-"+plan.path("revision").asInt()+"\""));}
    }
    private ObjectNode payload(JsonNode identity) throws Exception {
        JsonNode record=null;
        var grants=new HashSet<String>();identity.path("topicGrants").forEach(n->grants.add(n.asText()));
        for(var candidate:catalog.path("records"))if(candidate.path("contentType").asText().equals("theory") && iterable(candidate.path("topicIds")).stream().anyMatch(grants::contains)){record=candidate;break;}
        if(record==null)for(var candidate:catalog.path("records"))if(iterable(candidate.path("topicIds")).stream().anyMatch(grants::contains)){record=candidate;break;}
        check(record!=null,"Trusted catalog contains a granted fixture");
        ObjectNode body;try(var input=getClass().getResourceAsStream("/accounts/generated-plan.json")){body=(ObjectNode)mapper.readTree(input);}
        String topic=iterable(record.path("topicIds")).stream().filter(grants::contains).findFirst().orElseThrow();
        var snapshot=(ObjectNode)body.path("snapshot");var config=(ObjectNode)snapshot.path("config");
        config.putArray("topicIds").add(topic);config.putArray("accessTopicIds").add(topic);config.putObject("familiarity").put(topic,"new");
        ((ObjectNode)snapshot.path("includedTopics").get(0)).put("id",topic).put("path",topic.split(":")[0]);
        var assignment=(ObjectNode)snapshot.path("days").get(0).path("assignments").get(0);
        assignment.put("id","probe-session-"+record.path("id").asText()).put("sourceContentId",record.path("id").asText()).put("topicId",topic).put("contentType",record.path("contentType").asText()).put("activity",record.path("contentType").asText().equals("dsa-problem")?"Practice":record.path("contentType").asText().equals("theory")?"Understand":"Apply");assignment.set("route",record.path("route").deepCopy());
        ((tools.jackson.databind.node.ArrayNode)snapshot.path("weeks").get(0).path("days")).set(0,snapshot.path("days").get(0).deepCopy());
        ((ObjectNode)body.path("provenance")).put("catalogVersion",catalog.path("catalogVersion").asText()).put("rankingVersion",catalog.path("rankingVersions").get(0).asText());return body;
    }
    private List<String> iterable(JsonNode value){var result=new ArrayList<String>();value.forEach(v->result.add(v.asText()));return result;}
    private ObjectNode note(JsonNode plan,String text) {
        String content=plan.path("snapshot").path("days").get(0).path("assignments").get(0).path("sourceContentId").asText();
        var body=mapper.createObjectNode().put("expectedRevision",plan.path("revision").asInt()).put("versionId",plan.path("versionId").asText());body.putArray("operations").addObject().put("type","setNote").put("canonicalContentId",content).put("text",text);return body;
    }
    private void checkpoint(Client client,ObjectNode state) throws Exception {state.set("csrf",client.csrf);client.saveCookies();writePrivate(statePath,state);}
    private void lifecycle(String phase) throws Exception {
        var client=new Client("learner01");
        if(phase.equals("prepare")) {
            check(!Files.exists(statePath) && !Files.exists(cookiePath),"New checkpoint does not overwrite existing work");
            var identity=client.login();var plan=client.request("POST","/plans",payload(identity),UUID.randomUUID().toString(),201).path("data");
            boolean checkpointed=false;
            try {
                plan=client.request("POST","/plans/"+plan.path("planId").asText()+"/activity",note(plan,"Restart persistence probe"),UUID.randomUUID().toString(),200).path("data");
                var state=mapper.createObjectNode().put("planId",plan.path("planId").asText()).put("pendingKey",UUID.randomUUID().toString()).put("outageObserved",false);
                state.set("expectedPlan",plan);state.set("pendingBody",note(plan,"After outage retry"));checkpoint(client,state);checkpointed=true;
            } finally {if(!checkpointed){var latest=client.request("GET","/plans/"+plan.path("planId").asText(),null,null,200).path("data");client.delete(latest);}}
            return;
        }
        var state=(ObjectNode)mapper.readTree(Files.readString(statePath));String id=UUID.fromString(state.path("planId").asText()).toString();String route="/plans/"+id;
        client.csrf=(ObjectNode)state.path("csrf");client.loadCookies();
        if(phase.equals("outage") || phase.equals("network-outage")) {
            if(phase.equals("network-outage")) {
                var anonymous=new Client("learner01");anonymous.token();long started=System.nanoTime();
                var login=HttpRequest.newBuilder(base.resolve("/api/v1/auth/login")).timeout(Duration.ofSeconds(20))
                    .header(anonymous.csrf.path("headerName").asText(),anonymous.csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=learner01&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build();
                var failure=anonymous.http.send(login,HttpResponse.BodyHandlers.ofString());long elapsed=(System.nanoTime()-started)/1_000_000;
                check(failure.statusCode()==503 && mapper.readTree(failure.body()).path("code").asText().equals("ACCOUNT_STORAGE_UNAVAILABLE"),"Unresponsive database produces retryable login failure");
                check(elapsed<15000,"Network login failure bounded below15seconds: "+elapsed+"ms");
                anonymous.request("GET","/status",null,null,200);
            }
            var response=client.request("POST",route+"/activity",state.path("pendingBody"),state.path("pendingKey").asText(),503);
            check(response.path("code").asText().equals("ACCOUNT_STORAGE_UNAVAILABLE"),"Stable retryable storage code");
            check(client.response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),"Outage response cannot be cached");state.put("outageObserved",true);checkpoint(client,state);return;
        }
        client.login();var plan=client.request("GET",route,null,null,200).path("data");
        if(phase.equals("cleanup")){client.delete(plan);Files.delete(statePath);Files.deleteIfExists(cookiePath);return;}
        check(plan.equals(state.path("expectedPlan")),"Exact snapshot, progress, note and revision survive lifecycle");
        if(state.path("outageObserved").asBoolean()) {
            var result=client.request("POST",route+"/activity",state.path("pendingBody"),state.path("pendingKey").asText(),200).path("data");
            check(result.path("revision").asInt()==plan.path("revision").asInt()+1,"Recovered write increments revision once");
            var replay=client.request("POST",route+"/activity",state.path("pendingBody"),state.path("pendingKey").asText(),200).path("data");check(replay.equals(result),"Recovered retry replays exact saved state");
            state.set("expectedPlan",result);state.put("outageObserved",false).put("pendingKey",UUID.randomUUID().toString());state.set("pendingBody",note(result,"After outage retry"));
        }
        checkpoint(client,state);
    }
    private void restoreAccounts() throws Exception {
        var expected=mapper.readTree(Files.readString(privatePath(required("ATDD_PROBE_RESTORE"))));
        check(expected.path("accounts").size()==10,"Restore inventory has ten synthetic accounts");int plans=0;
        for(var account:expected.path("accounts")) {
            String username=account.path("username").asText();check(username.matches("learner(?:0[1-9]|10)"),"Restore uses only synthetic users");
            var client=new Client(username);var identity=client.login();check(identity.path("accountId").equals(account.path("accountId")),"Restored identity matches database");
            for(var plan:expected.path("plans"))if(plan.path("accountId").equals(account.path("accountId"))) {
                var actual=client.request("GET","/plans/"+UUID.fromString(plan.path("planId").asText()),null,null,200).path("data");
                for(var field:plan.properties())if(!field.getKey().equals("accountId"))check(actual.path(field.getKey()).equals(field.getValue()),"Restored plan field matches database: "+field.getKey());plans++;
            }
            client.request("POST","/auth/logout",null,null,204);
        }
        check(plans==expected.path("plans").size(),"Every restored plan was verified");
    }
}
