"""Restore indexing across all 31 translated Brass Pawn locales and remove viewer-response x-robots-tag noindex."""
import boto3, json, pathlib
base=pathlib.Path(__file__).parent
client=boto3.client('cloudfront'); name='brasspawn-english-indexing'; distribution='E1K81IYSMEAXO9'
code=(base/'english-only-indexing.js').read_bytes()
config={'Comment':'Allow search indexing for all translated Brass Pawn locales','Runtime':'cloudfront-js-2.0'}
try:
    old=client.describe_function(Name=name,Stage='DEVELOPMENT')
    current=client.update_function(Name=name,IfMatch=old['ETag'],FunctionConfig=config,FunctionCode=code)
except client.exceptions.NoSuchFunctionExists:
    current=client.create_function(Name=name,FunctionConfig=config,FunctionCode=code)
checks=[]
for uri, expected in [('/de/today/sample/index.html',False),('/ja',False),('/it/reports/chess-olympiad-2026',False),('/articles/king-pawn-opposition',False),('/',False)]:
    event={'version':'1.0','context':{'eventType':'viewer-response'},'viewer':{'ip':'192.0.2.1'},'request':{'method':'GET', 'uri':uri,'querystring':{},'headers':{},'cookies':{}},'response':{'statusCode':200,'statusDescription':'OK','headers':{},'cookies':{}}}
    result=client.test_function(Name=name,IfMatch=current['ETag'],Stage='DEVELOPMENT',EventObject=json.dumps(event).encode())['TestResult']
    assert not result.get('FunctionErrorMessage'), result.get('FunctionErrorMessage')
    output=json.loads(result['FunctionOutput'])['response']; actual='x-robots-tag' in output['headers']; assert actual==expected,uri
    checks.append({'path':uri,'noindex':actual})
client.publish_function(Name=name,IfMatch=current['ETag'])
arn=current['FunctionSummary']['FunctionMetadata']['FunctionARN']
state=client.get_distribution_config(Id=distribution); cfg=state['DistributionConfig']; behavior=cfg['DefaultCacheBehavior']
associations=behavior.get('FunctionAssociations',{}).get('Items',[])
remaining=[x for x in associations if not (x['FunctionARN']==arn and x['EventType']=='viewer-response')]
if len(remaining) != len(associations):
    behavior['FunctionAssociations']={'Quantity':len(remaining),'Items':remaining} if remaining else {'Quantity':0}
    client.update_distribution(Id=distribution,IfMatch=state['ETag'],DistributionConfig=cfg)
audit={'function':name,'distribution':distribution,'tests':checks,'preserved_request_router':True,'viewer_response_removed':True}
(base.parent/'research/growth-20261024/indexing-edge-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
print(json.dumps(audit))
