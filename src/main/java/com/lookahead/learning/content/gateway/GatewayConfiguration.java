package com.lookahead.learning.content.gateway;

import com.lookahead.learning.content.oauth.OAuthSettings;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Configuration
@Profile("gateway")
public class GatewayConfiguration {
    @Bean OAuthSettings gatewaySettings(Environment environment) { return OAuthSettings.from(environment); }
    @Bean ClientRegistrationRepository gatewayClient(OAuthSettings settings) {
        var client=ClientRegistration.withRegistrationId("lookahead")
                .clientId(settings.clientId()).clientSecret(settings.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(settings.frontend()+"/login/oauth2/code/lookahead")
                .scope("openid","profile","account","content","support")
                .authorizationUri(settings.issuer()+"/oauth2/authorize")
                .tokenUri(settings.upstream()+"/oauth2/token")
                .jwkSetUri(settings.upstream()+"/oauth2/jwks")
                .userInfoUri(settings.upstream()+"/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB).issuerUri(settings.issuer())
                .providerConfigurationMetadata(Map.of("end_session_endpoint",settings.issuer()+"/connect/logout"))
                .clientName("Look Ahead").build();
        return new InMemoryClientRegistrationRepository(client);
    }
    @Bean OAuth2AuthorizedClientRepository gatewayClients() { return new HttpSessionOAuth2AuthorizedClientRepository(); }
    @Bean OAuth2AuthorizedClientManager gatewayClientManager(ClientRegistrationRepository clients, OAuth2AuthorizedClientRepository authorized) {
        var manager=new DefaultOAuth2AuthorizedClientManager(clients,authorized);
        var refresh=new RestClientRefreshTokenTokenResponseClient();refresh.setRestClient(tokenHttp());
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode()
                .refreshToken(configurer->configurer.accessTokenResponseClient(refresh)).build());
        return manager;
    }
    private static SimpleClientHttpRequestFactory boundedRequests() {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(7000);
        return factory;
    }
    @Bean RestClient gatewayHttp() { return RestClient.builder().requestFactory(boundedRequests()).build(); }
    private static RestClient tokenHttp() {
        return RestClient.builder().requestFactory(boundedRequests()).configureMessageConverters(converters->{
            converters.addCustomConverter(new FormHttpMessageConverter());
            converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
    }
    @Bean JwtDecoderFactory<ClientRegistration> gatewayIdTokenDecoders() {
        var decoders=new java.util.concurrent.ConcurrentHashMap<String,JwtDecoder>();
        return registration->decoders.computeIfAbsent(registration.getRegistrationId(),key->{
            var decoder=NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .restOperations(new RestTemplate(boundedRequests())).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),new OidcIdTokenValidator(registration)));
            return decoder;
        });
    }
    @Bean SecurityFilterChain gatewaySecurity(HttpSecurity http, OAuthSettings settings, ClientRegistrationRepository clients,
                                              OAuth2AuthorizedClientRepository authorized) throws Exception {
        var resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var exchange=new RestClientAuthorizationCodeTokenResponseClient();exchange.setRestClient(tokenHttp());
        var userInfo=new DefaultOAuth2UserService();var userHttp=new RestTemplate(boundedRequests());
        userHttp.setErrorHandler(new OAuth2ErrorResponseErrorHandler());userInfo.setRestOperations(userHttp);
        var oidcUsers=new OidcUserService();oidcUsers.setOauth2UserService(userInfo);
        http.headers(headers -> headers.frameOptions(frame -> frame.disable())
                .addHeaderWriter(new GatewayFrameHeaders()));
        http.csrf(csrf->csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session->session.sessionFixation(fixation->fixation.changeSessionId()))
                .authorizeHttpRequests(auth->auth
                        .requestMatchers("/bff/login","/bff/api/v1/auth/csrf","/oauth2/authorization/**","/login/oauth2/code/**","/content/**","/actuator/health","/actuator/health/**").permitAll()
                        .requestMatchers("/bff/api/v1/**").authenticated().anyRequest().denyAll())
                .exceptionHandling(errors->errors
                        .authenticationEntryPoint((request,response,error)->{response.setStatus(401);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in to continue\"}");})
                        .accessDeniedHandler((request,response,error)->{response.setStatus(403);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\"REQUEST_REJECTED\",\"message\":\"Refresh the security token and retry\"}");}))
                .oauth2Login(login->login.authorizationEndpoint(endpoint->endpoint.authorizationRequestResolver(resolver))
                        .tokenEndpoint(endpoint->endpoint.accessTokenResponseClient(exchange))
                        .userInfoEndpoint(endpoint->endpoint.oidcUserService(oidcUsers))
                        .authorizedClientRepository(authorized)
                        .successHandler((request,response,authentication)->{
                            var session=request.getSession();Object saved=session.getAttribute("learningReturnTo");session.removeAttribute("learningReturnTo");
                            response.sendRedirect(settings.frontend()+safeReturn(saved instanceof String value?value:null));
                        })
                        .failureHandler((request,response,error)->response.sendRedirect(settings.frontend()+"/sign-in?error=oauth")))
                .oauth2Client(client->client.authorizedClientRepository(authorized));
        return http.build();
    }
    public static String safeReturn(String value) {
        return value!=null && value.matches("^/(?:study-plan|learn|grow|look-ahead|search|support|author)(?:[/?].*)?$")
                && !value.contains("\\") && !value.contains("\r") && !value.contains("\n") ? value : "/study-plan";
    }
}
