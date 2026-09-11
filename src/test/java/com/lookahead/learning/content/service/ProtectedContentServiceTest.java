package com.lookahead.learning.content.service;
import com.lookahead.learning.content.exception.AccountFailure;
import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectedContentServiceTest {
    @TempDir Path directory;
    private final JsonMapper mapper=new JsonMapper();
    private final AccountRepository accounts=mock(AccountRepository.class);
    private final AccountPrincipal learner=new AccountPrincipal(UUID.randomUUID(),"learner","Learner","",true);
    private ProtectedContentPolicy policy() throws Exception {
        var entries=new ArrayList<Map<String,Object>>();
        for(String tier:List.of("public","free","pro")) {
            byte[] bytes=("{\"title\":\""+tier+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(directory.resolve(tier+".json"),bytes);
            entries.add(Map.of("path","/content/"+tier+".json","sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"mediaType","application/json","tier",tier,"scopes",List.of("learn:sample-course"),"contentIds",List.of(tier+"-lesson")));
        }
        Files.writeString(directory.resolve("manifest.json"),mapper.writeValueAsString(Map.of("schemaVersion","content-publication/v1","version","test-v1","assets",entries)));
        return new ProtectedContentPolicy(mapper,directory.resolve("manifest.json").toString(),directory.toString());
    }
    private void denied(Runnable call,int status,String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AccountFailure.class,error->{assertThat(error.status()).isEqualTo(status);assertThat(error.code()).isEqualTo(code);});
    }
    @Test void publicIsAnonymousButFreeRequiresAnEnabledCurrentAccount() throws Exception {
        var service=new ProtectedContentService(policy(),accounts);
        assertThat(service.read("/content/public.json",null).mediaType()).isEqualTo("application/json");
        denied(()->service.read("/content/free.json",null),401,"AUTHENTICATION_REQUIRED");
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        assertThat(service.read("/content/free.json",learner).bytes()).isNotEmpty();
        when(accounts.isEnabled(learner.accountId())).thenReturn(false);
        denied(()->service.read("/content/free.json",learner),401,"AUTHENTICATION_REQUIRED");
    }
    @Test void courseGrantIsRecheckedOnEveryReadAndCannotUnlockOtherCourses() throws Exception {
        var service=new ProtectedContentService(policy(),accounts);
        when(accounts.isEnabled(learner.accountId())).thenReturn(true);
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("grow:another-course"));
        denied(()->service.read("/content/pro.json",learner),403,"CONTENT_SCOPE_REQUIRED");
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of("learn:sample-course"));
        assertThat(service.read("/content/pro.json",learner).bytes()).isNotEmpty();
        when(accounts.findTopicGrants(learner.accountId())).thenReturn(Set.of());
        denied(()->service.read("/content/pro.json",learner),403,"CONTENT_SCOPE_REQUIRED");
    }
    @Test void excludesUnlistedFilesAndRejectsTraversalAndChangedBytes() throws Exception {
        var policy=policy();var service=new ProtectedContentService(policy,accounts);
        for(String path:List.of("/content/manifest.json","/content/../public.json","/content/%2e%2e/public.json","/content/public.json/","/content/public.json%00"))denied(()->service.read(path,null),404,"CONTENT_NOT_FOUND");
        assertThat(policy.freeContentIds()).containsExactlyInAnyOrder("free-lesson","public-lesson");
        Files.writeString(directory.resolve("public.json"),"changed");
        denied(()->service.read("/content/public.json",null),503,"CONTENT_UNAVAILABLE");
    }
    @Test void symlinkReplacementCannotCrossThePublicationBoundary() throws Exception {
        var service=new ProtectedContentService(policy(),accounts);
        Files.delete(directory.resolve("public.json"));
        Files.createSymbolicLink(directory.resolve("public.json"),directory.resolve("free.json"));
        denied(()->service.read("/content/public.json",null),503,"CONTENT_UNAVAILABLE");
    }
    @Test void disabledPublicationServesNothingAndPartialConfigurationFailsClosed() {
        assertThat(new ProtectedContentPolicy(mapper,"","").find("/content/public.json")).isEmpty();
        assertThatThrownBy(()->new ProtectedContentPolicy(mapper,"",directory.toString())).isInstanceOf(IllegalStateException.class);
    }
}
