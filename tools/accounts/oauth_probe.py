#!/usr/bin/env python3
"""Exercise real OAuth protocol boundaries on the isolated local 4332 server. Never logs credentials or tokens."""
import argparse, base64, hashlib, http.cookiejar, json, secrets, sys
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit, parse_qs
from urllib.request import ProxyHandler, build_opener, HTTPCookieProcessor, HTTPRedirectHandler, Request

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args): return None

class Probe:
    def __init__(self, secret, password):
        self.base='http://127.0.0.1:4332'; self.issuer='http://127.0.0.1:4331'
        self.client='lookahead-web-gateway';self.secret=secret;self.password=password
        self.cookies=http.cookiejar.CookieJar();self.http=build_opener(HTTPCookieProcessor(self.cookies),NoRedirect());self.checks=[]
    def call(self,path,form=None,headers=None,method=None):
        headers=dict(headers or {});data=None
        if form is not None:data=urlencode(form).encode();headers['Content-Type']='application/x-www-form-urlencoded'
        request=Request(self.base+path,data=data,headers=headers,method=method)
        try:response=self.http.open(request,timeout=12)
        except HTTPError as error:response=error
        body=response.read();payload=None
        try:payload=json.loads(body)
        except (ValueError,UnicodeDecodeError):pass
        return response.status,response.headers,payload
    def check(self,condition,name):
        if not condition:raise AssertionError(name)
        self.checks.append(name)
    def basic(self,secret=None):return {'Authorization':'Basic '+base64.b64encode((self.client+':'+(secret or self.secret)).encode()).decode()}
    def authorize(self,scope='openid profile account content support', **overrides):
        verifier=secrets.token_urlsafe(48);challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
        params={'client_id':self.client,'response_type':'code','redirect_uri':self.issuer+'/login/oauth2/code/lookahead','scope':scope,'state':secrets.token_urlsafe(24),'nonce':secrets.token_urlsafe(24),'code_challenge':challenge,'code_challenge_method':'S256'}
        params.update(overrides);params={key:value for key,value in params.items() if value is not None}
        status,headers,data=self.call('/oauth2/authorize?'+urlencode(params));query=parse_qs(urlsplit(headers.get('Location','')).query)
        return status,query,verifier,headers
    def exchange(self,code,verifier,headers=None):
        return self.call('/oauth2/token',{'grant_type':'authorization_code','code':code,'redirect_uri':self.issuer+'/login/oauth2/code/lookahead','code_verifier':verifier},headers if headers is not None else self.basic())
    def run(self):
        status,_,metadata=self.call('/.well-known/openid-configuration')
        self.check(status==200 and metadata['issuer']==self.issuer,'Discovery fixes the issuer to the configured frontend origin')
        status,_,jwks=self.call('/oauth2/jwks')
        self.check(status==200 and all(not(set(key)&{'d','p','q','dp','dq','qi'}) for key in jwks['keys']),'JWKS exposes only public signing material')
        status,_,csrf=self.call('/api/v1/auth/csrf');token=csrf['data']
        status,_,account=self.call('/api/v1/auth/login',{'username':'learner01','password':self.password},{token['headerName']:token['token']})
        self.check(status==200,'Synthetic account signs in at the authorization server')
        status,_,_=self.call('/api/v1/plans');self.check(status==401,'Identity session cookie alone cannot access resource APIs')
        for name,override in [('missing PKCE',{'code_challenge':None,'code_challenge_method':None}),('plain PKCE',{'code_challenge_method':'plain'}),('unregistered redirect',{'redirect_uri':'https://untrusted.example/callback'}),('unregistered client',{'client_id':'untrusted-client'}),('implicit grant',{'response_type':'token'})]:
            status,query,_,headers=self.authorize(**override)
            self.check('code' not in query and (status>=400 or 'error' in query),name+' is rejected without a code')
            if name=='unregistered redirect':self.check(not headers.get('Location','').startswith('https://untrusted.example'),'Invalid redirects are never followed')
        status,query,verifier,_=self.authorize();self.check(status==302 and 'code' in query,'S256 authorization request issues a code')
        code=query['code'][0]
        status,_,_=self.exchange(code,verifier,{});self.check(status in [400,401],'Code exchange requires confidential client authentication')
        status,_,_=self.exchange(code,verifier,self.basic('incorrect-secret'));self.check(status==401,'Incorrect client secret is rejected')
        status,_,tokens=self.exchange(code,verifier);self.check(status==200 and 'access_token' in tokens and 'refresh_token' in tokens,'Authenticated code exchange returns access and refresh tokens')
        access=tokens['access_token'];refresh=tokens['refresh_token'];claims=json.loads(base64.urlsafe_b64decode(access.split('.')[1]+'=='))
        barrier=Barrier(12)
        def concurrentRead(_):
            barrier.wait(timeout=10)
            request=Request(self.base+'/api/v1/auth/me',headers={'Authorization':'Bearer '+access})
            try:
                with build_opener(ProxyHandler({})).open(request,timeout=12) as response:
                    response.read()
                    return response.status
            except HTTPError as error:return error.code
        with ThreadPoolExecutor(max_workers=12) as workers:
            statuses=list(workers.map(concurrentRead,range(24)))
        self.check(statuses==[200]*24,'Twenty-four authenticated reads at concurrency twelve complete without nested JDBC pool starvation')
        self.check(claims['iss']==self.issuer and claims['aud'] in ['lookahead-api',['lookahead-api']] and claims['client_id']==self.client and claims['sub']==account['data']['accountId'],'Access token binds issuer, API audience, client and immutable account identity')
        self.check(claims['exp']-claims['iat']<=300,'Access token lifetime is bounded to five minutes')
        status,_,me=self.call('/api/v1/auth/me',headers={'Authorization':'Bearer '+access});self.check(status==200 and me['data']['accountId']==claims['sub'],'Valid access token resolves the current account')
        status,_,_=self.exchange(code,verifier);self.check(status==400,'Authorization code replay is rejected')
        # A replay revokes the original authorization; obtain an independent grant for rotation.
        _,query,verifier,_=self.authorize();status,_,tokens=self.exchange(query['code'][0],verifier);self.check(status==200,'Independent grant is issued after replay rejection')
        access=tokens['access_token'];refresh=tokens['refresh_token']
        status,_,rotated=self.call('/oauth2/token',{'grant_type':'refresh_token','refresh_token':refresh},self.basic())
        self.check(status==200 and rotated['refresh_token']!=refresh,'Refresh tokens rotate')
        status,_,_=self.call('/oauth2/token',{'grant_type':'refresh_token','refresh_token':refresh},self.basic());self.check(status==400,'Reusing the previous refresh token is rejected')
        access=rotated['access_token'];refresh=rotated['refresh_token']
        status,_,_=self.call('/oauth2/revoke',{'token':access},self.basic());self.check(status==200,'Access token revocation succeeds')
        status,_,_=self.call('/api/v1/plans',headers={'Authorization':'Bearer '+access});self.check(status==401,'A revoked access token is rejected immediately')
        self.call('/oauth2/revoke',{'token':refresh},self.basic())
        status,_,_=self.call('/oauth2/token',{'grant_type':'refresh_token','refresh_token':refresh},self.basic());self.check(status==400,'Revoked refresh token cannot mint new tokens')
        _,query,verifier,_=self.authorize(scope='openid profile');status,_,limited=self.exchange(query['code'][0],verifier)
        self.check(status==200,'A valid limited-scope grant is issued')
        status,_,_=self.call('/api/v1/plans',headers={'Authorization':'Bearer '+limited['access_token']});self.check(status==403,'Missing account scope cannot read plans')
        self.call('/oauth2/revoke',{'token':limited['refresh_token']},self.basic())
        _,query,verifier,_=self.authorize();status,_,_=self.exchange(query['code'][0],'incorrect-verifier');self.check(status==400,'Wrong PKCE verifier cannot exchange a code')
        for grant in ['password','client_credentials']:
            status,_,_=self.call('/oauth2/token',{'grant_type':grant,'username':'learner01','password':'synthetic'},self.basic());self.check(status==400,grant+' grant is disabled')
        status,_,_=self.call('/api/v1/plans',headers={'Authorization':'Bearer forged.token.value'});self.check(status==401,'Forged bearer tokens are rejected')
        status,_,_=self.call('/api/v1/auth/options',headers={'Origin':'https://untrusted.example'});self.check(status==403,'Untrusted browser origin is rejected')
        _,query,verifier,_=self.authorize();status,_,logoutTokens=self.exchange(query['code'][0],verifier)
        self.check(status==200,'Logout verification obtains a fresh authorization')
        self.call('/oauth2/revoke',{'token':logoutTokens['access_token']},self.basic())
        self.call('/oauth2/revoke',{'token':logoutTokens['refresh_token']},self.basic())
        status,headers,_=self.call('/connect/logout?'+urlencode({'id_token_hint':logoutTokens['id_token'],'post_logout_redirect_uri':self.issuer+'/sign-in'}))
        self.check(status in [302,303] and headers.get('Location')==self.issuer+'/sign-in','OIDC logout ends the identity session after token revocation')
        return {'passed':True,'checks':self.checks,'count':len(self.checks),'baseUrl':self.base,'credentialsLogged':False}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-secret-file',type=Path,required=True);parser.add_argument('--password-file',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    probe=Probe(args.client_secret_file.read_text().strip(),args.password_file.read_text().strip())
    try:report=probe.run()
    except Exception as error:
        # Protocol payloads and callback locations may contain secrets; write only the check name.
        report={'passed':False,'checks':probe.checks,'count':len(probe.checks),'failure':str(error) if isinstance(error,AssertionError) else type(error).__name__}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report));return 0 if report['passed'] else 1
if __name__=='__main__':sys.exit(main())
