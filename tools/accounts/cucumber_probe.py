#!/usr/bin/env python3
"""Launch Java/Cucumber account contracts; keep orchestration CLI compatible and logs private."""
import argparse,json,os,subprocess,sys,uuid
from pathlib import Path
from urllib.parse import urlsplit
root=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--password-file',required=True,type=Path)
    parser.add_argument('--catalog',type=Path,default=root/'src/test/resources/accounts/catalog.json')
    parser.add_argument('--phase',choices=['full','prepare','resume','outage','network-outage','cleanup','restore'],default='full')
    parser.add_argument('--state-file',type=Path)
    parser.add_argument('--restore-file',type=Path)
    parser.add_argument('--standalone',action='store_true',help='Explicitly authorize the full synthetic suite on a standalone loopback API port')
    parser.add_argument('--account-guard',action='store_true',help='Account guards always run in the full Cucumber profile')
    args=parser.parse_args();uri=urlsplit(args.base_url)
    ports={4323} if args.phase=='restore' else {4322} if args.phase=='full' else {4320,4322}
    if args.standalone and args.phase=='full' and uri.port not in [4200,4316,4320,4323]:ports.add(uri.port)
    if uri.scheme!='http' or uri.hostname not in ['127.0.0.1','localhost'] or uri.port not in ports or uri.username or uri.password or uri.path not in ['', '/'] or uri.query or uri.fragment:parser.error('Use the documented local service port; full suite only runs on isolated4322')
    outputDir=root/'.codex-scratch/cucumber-probes'
    if outputDir.is_symlink():parser.error('Probe output must not follow symlinks')
    outputDir.mkdir(parents=True,exist_ok=True)
    runId=uuid.uuid4().hex;log=outputDir/(runId+'.log');output=outputDir/(runId+'.json')
    env=os.environ.copy();env.update(ATDD_PASSWORD_FILE=str(args.password_file.absolute()),ATDD_PROBE_PHASE=args.phase,ATDD_PROBE_BASE_URL=args.base_url.rstrip('/'),ATDD_PROBE_CATALOG=str(args.catalog.absolute()),ATDD_PROBE_OUTPUT=str(output))
    if args.standalone:env['ATDD_STANDALONE']='true'
    if args.state_file:env['ATDD_PROBE_STATE']=str(args.state_file.absolute())
    if args.restore_file:env['ATDD_PROBE_RESTORE']=str(args.restore_file.absolute())
    if args.phase=='full':env.update(ATDD_LIVE_ENABLED='true',ATDD_BASE_URL=args.base_url.rstrip('/'));tags='@live and not @mail'
    else:tags='@checkpoint and @'+args.phase
    command=['./mvnw','-q','-Dtest=CucumberAtddTest','-Dcucumber.filter.tags='+tags,'test']
    with log.open('x') as handle:
        log.chmod(0o600);result=subprocess.run(command,cwd=root,env=env,stdout=handle,stderr=subprocess.STDOUT)
    if args.phase=='full':
        report=root/'target/cucumber/report.json'
        scenarios=[item for feature in json.loads(report.read_text()) for item in feature.get('elements',[]) if item.get('type')=='scenario'] if report.exists() else []
        successful=[item for item in scenarios if all(step.get('result',{}).get('status')=='passed' for group in ['steps','before','after'] for step in item.get(group,[]))]
        data={'result':'passed' if result.returncode==0 and scenarios and len(successful)==len(scenarios) else 'failed','phase':'full','checksPassed':len(successful),'checks':[item['name'] for item in successful],'limitations':['Real isolated HTTP scenarios; production identity and frontend scheduling need their own checks']}
    else:data=json.loads(output.read_text()) if output.exists() else {'result':'failed','phase':args.phase,'checksPassed':0,'failure':'The Cucumber probe did not produce a result'}
    data['log']=str(log)
    if result.returncode:data['result']='failed'
    print(json.dumps(data,indent=2));return 0 if data['result']=='passed' else 1
if __name__=='__main__':sys.exit(main())
