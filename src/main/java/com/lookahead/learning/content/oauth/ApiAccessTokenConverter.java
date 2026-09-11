package com.lookahead.learning.content.oauth;

import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import java.util.UUID;

/** JWT signature/issuer/time validation runs first; this enforces app, revocation, scope and current account identity. */
public final class ApiAccessTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final OAuthSettings settings;
    private final AccountRepository accounts;
    private final OAuth2AuthorizationService authorizations;
    public ApiAccessTokenConverter(OAuthSettings settings,AccountRepository accounts,OAuth2AuthorizationService authorizations) {
        this.settings=settings;this.accounts=accounts;this.authorizations=authorizations;
    }
    @Override public AbstractAuthenticationToken convert(Jwt token) {
        if(token.getAudience()==null || !token.getAudience().contains("lookahead-api")
                || token.getSubject()==null || !settings.clientId().equals(token.getClaimAsString("client_id"))) throw invalid();
        var authorization=authorizations.findByToken(token.getTokenValue(),OAuth2TokenType.ACCESS_TOKEN);
        if(authorization==null || authorization.getAccessToken()==null || !authorization.getAccessToken().isActive()
                || !token.getSubject().equals(authorization.getPrincipalName())) throw invalid();
        UUID id;
        try { id=UUID.fromString(token.getSubject()); } catch(RuntimeException error) { throw invalid(); }
        var account=accounts.findById(id).filter(a->a.enabled()).orElseThrow(ApiAccessTokenConverter::invalid);
        var principal=new AccountPrincipal(account.accountId(),account.username(),account.displayName(),null,true);
        return UsernamePasswordAuthenticationToken.authenticated(principal,null,new JwtGrantedAuthoritiesConverter().convert(token));
    }
    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),"Invalid access token");
    }
}
