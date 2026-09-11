package com.lookahead.learning.content.oauth;

import com.lookahead.learning.content.security.AccountPrincipal;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.Duration;
import java.util.*;

@Configuration
@Profile("oauth-server")
public class OAuthServerConfiguration {
    @Bean OAuthSettings oauthSettings(Environment environment) { return OAuthSettings.from(environment); }
    @Bean AuthorizationServerSettings authorizationServerSettings(OAuthSettings settings) {
        return AuthorizationServerSettings.builder().issuer(settings.issuer()).build();
    }
    @Bean RegisteredClientRepository registeredClients(JdbcTemplate jdbc, PasswordEncoder encoder, OAuthSettings settings) {
        var repository=new JdbcRegisteredClientRepository(jdbc);
        var old=repository.findByClientId(settings.clientId());
        String encoded=old!=null && encoder.matches(settings.clientSecret(),old.getClientSecret()) ? old.getClientSecret() : encoder.encode(settings.clientSecret());
        var client=RegisteredClient.withId(old==null?"lookahead-web-gateway-v1":old.getId())
                .clientId(settings.clientId()).clientSecret(encoded).clientName("Look Ahead")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(settings.frontend()+"/login/oauth2/code/lookahead")
                .postLogoutRedirectUri(settings.frontend()+"/sign-in")
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope("account").scope("content").scope("support")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().authorizationCodeTimeToLive(Duration.ofMinutes(1))
                        .accessTokenTimeToLive(Duration.ofMinutes(5)).refreshTokenTimeToLive(Duration.ofHours(8)).reuseRefreshTokens(false).build()).build();
        repository.save(client);
        return repository;
    }
    @Bean OAuth2AuthorizationService authorizations(JdbcTemplate jdbc, RegisteredClientRepository clients,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        return new TransactionalAuthorizationService(
                new StablePrincipalAuthorizationService(new JdbcOAuth2AuthorizationService(jdbc,clients)), transactions);
    }
    @Bean OAuth2AuthorizationConsentService consents(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc,clients);
    }
    @Bean JWKSource<SecurityContext> signingKeys(Environment environment) throws Exception {
        byte[] privateBytes=pem(Path.of(environment.getRequiredProperty("app.oauth.signing-private-key")),"PRIVATE KEY");
        byte[] publicBytes=pem(Path.of(environment.getRequiredProperty("app.oauth.signing-public-key")),"PUBLIC KEY");
        var factory=KeyFactory.getInstance("RSA");
        var privateKey=(RSAPrivateKey)factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        var publicKey=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(publicBytes));
        if(publicKey.getModulus().bitLength()<3072 || !publicKey.getModulus().equals(privateKey.getModulus()))
            throw new IllegalStateException("OAuth requires matching RSA signing keys of at least 3072 bits");
        var kid=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicBytes)).substring(0,24);
        var key=new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid).build();
        var keys=new JWKSet(key);
        return (selector,context)->selector.select(keys);
    }
    private static byte[] pem(Path path,String kind) throws Exception {
        String value=Files.readString(path).replace("-----BEGIN "+kind+"-----","").replace("-----END "+kind+"-----","").replaceAll("\\s","");
        return Base64.getDecoder().decode(value);
    }
    @Bean JwtDecoder authorizationJwtDecoder(JWKSource<SecurityContext> keys, OAuthSettings settings) throws Exception {
        var publicKey=keys.get(new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).build()),null).getFirst().toRSAKey().toRSAPublicKey();
        var decoder=NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(settings.issuer()));
        return decoder;
    }
    @Bean OAuth2TokenCustomizer<JwtEncodingContext> accountTokenClaims(OAuthSettings settings) {
        return context->{
            String id=context.getPrincipal().getPrincipal() instanceof AccountPrincipal principal ? principal.accountId().toString() : context.getAuthorization().getPrincipalName();
            context.getClaims().subject(id);
            if("id_token".equals(context.getTokenType().getValue()) && context.getAuthorization()!=null) {
                String sessionHash=context.getAuthorization().getAttribute("lookahead.identity-session-hash");
                if(sessionHash!=null)context.getClaims().claim("sid",sessionHash);
            }
            if(OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                // JDBC's strict security mapper supports ArrayList, not JDK List.of implementation types.
                context.getClaims().audience(new ArrayList<>(List.of("lookahead-api")));
                context.getClaims().claim("client_id",context.getRegisteredClient().getClientId());
            }
        };
    }
    @Bean @Order(1) SecurityFilterChain oauthAuthorizationSecurity(HttpSecurity http, OAuthSettings settings) throws Exception {
        var server=new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(server.getEndpointsMatcher())
                .with(server,configurer->configurer.oidc(oidc->oidc.logoutEndpoint(logout->logout
                        .errorResponseHandler((request,response,error)->{
                            var oauthError=((OAuth2AuthenticationException)error).getError();
                            org.slf4j.LoggerFactory.getLogger(OAuthServerConfiguration.class).warn("OIDC logout rejected: {} ({})",oauthError.getErrorCode(),oauthError.getDescription());
                            response.setStatus(400);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");
                            response.getWriter().write("{\"code\":\"LOGOUT_REJECTED\",\"message\":\"Sign out could not be completed. Return to the account page and retry.\"}");
                        }))))
                .authorizeHttpRequests(auth->auth.anyRequest().authenticated())
                .requestCache(cache->cache.requestCache(new HttpSessionRequestCache()))
                .exceptionHandling(errors->errors.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(settings.frontend()+"/sign-in?oauth=continue")));
        return http.build();
    }
}
