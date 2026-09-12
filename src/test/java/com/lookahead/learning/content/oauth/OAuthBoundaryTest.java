package com.lookahead.learning.content.oauth;

import com.lookahead.learning.content.gateway.GatewayConfiguration;
import com.lookahead.learning.content.repository.AccountRepository;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuthBoundaryTest {
    OAuthSettings settings() { return new OAuthSettings("http://127.0.0.1:4331","lookahead-web-gateway","synthetic-long-client-secret-for-testing","http://content-api:8080","http://127.0.0.1:4331"); }
    @Test void arbitraryIssuerRedirectsAndWeakSecretsAreNotAccepted() {
        var environment=new MockEnvironment().withProperty("app.deployment-environment","local")
                .withProperty("app.oauth.issuer","http://127.0.0.1:4331").withProperty("app.oauth.frontend","http://127.0.0.1:4331")
                .withProperty("app.oauth.client-secret","synthetic-long-client-secret-for-testing");
        assertThat(OAuthSettings.from(environment).issuer()).isEqualTo(settings().issuer());
        assertThat(OAuthSettings.from(environment).toString()).doesNotContain("synthetic");
        environment.setProperty("app.deployment-environment","production");
        assertThatThrownBy(()->OAuthSettings.from(environment)).isInstanceOf(IllegalStateException.class);
        for(String bad:List.of("//evil.example","https://evil.example","/account","/learn\\evil","/learn\nelse")) assertThat(GatewayConfiguration.safeReturn(bad)).isEqualTo("/study-plan");
        assertThat(GatewayConfiguration.safeReturn("/author")).isEqualTo("/author");
        assertThat(GatewayConfiguration.safeReturn("/learn/core-java?day=2")).isEqualTo("/learn/core-java?day=2");
    }
    @Test void wrongAudienceOrClientCannotReachAccountOrAuthorizationStorage() {
        var accounts=mock(AccountRepository.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var authorizations=mock(OAuth2AuthorizationService.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var converter=new ApiAccessTokenConverter(settings(),accounts,authorizations);
        for(var claims:List.of(new String[]{"other-api","lookahead-web-gateway"},new String[]{"lookahead-api","other-client"})) {
            var token=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(UUID.randomUUID().toString())
                    .audience(List.of(claims[0])).claim("client_id",claims[1]).build();
            assertThatThrownBy(()->converter.convert(token)).isInstanceOf(OAuth2AuthenticationException.class);
        }
        verifyNoInteractions(accounts,authorizations);
    }
    @Test void cryptographicDecoderRejectsExpiredAndWrongIssuerTokens() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var pair=generator.generateKeyPair();
        var key=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey((RSAPrivateKey)pair.getPrivate()).keyID("synthetic-key").build();
        JWKSource<SecurityContext> source=(selector,context)->selector.select(new JWKSet(key));
        var decoder=new OAuthKeyConfiguration().authorizationJwtDecoder(source,settings());var encoder=new NimbusJwtEncoder(source);
        for(var value:List.of(new String[]{settings().issuer(),"-120"},new String[]{"https://other-issuer.example","300"})) {
            var claims=JwtClaimsSet.builder().issuer(value[0]).subject(UUID.randomUUID().toString()).issuedAt(Instant.now().minusSeconds(600))
                    .expiresAt(Instant.now().plusSeconds(Integer.parseInt(value[1]))).build();
            String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
            assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtValidationException.class);
        }
    }
}
