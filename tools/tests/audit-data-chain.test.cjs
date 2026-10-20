const {test}=require('node:test');
const assert=require('node:assert/strict');
const {audit}=require('../audit_data_chain.cjs');
test('reconciliation performs only reads after login and revokes its session',async()=>{
  const requests=[];
  const fake=async(url,options)=>{
    const route=new URL(url).pathname;requests.push({route,method:options.method});
    const value=route.endsWith('/login')?{token:'private-test-token'}:route.endsWith('/me')?{role:'ADMIN'}:
      route==='/api/farms'?[{ID:'farm-test'}]:route.endsWith('/data-quality')?{summary:{errorCount:0,warningCount:1}}:{};
    return {ok:true,json:async()=>value};
  };
  const report=await audit({FARM_AUDIT_PASSWORD:'private-test-password'},fake);
  assert.equal(report.mode,'READ_ONLY');assert.equal(report.reports.length,1);
  assert.deepEqual(requests.filter(r=>r.method!=='GET').map(r=>r.route),['/api/auth/login','/api/auth/logout']);
  assert(!JSON.stringify(report).includes('private-test'));
});
test('failed report read still logs out and never swallows the failure',async()=>{
  let logout=false;
  const fake=async(url)=>{
    const route=new URL(url).pathname;
    if(route.endsWith('/logout'))logout=true;
    if(route.endsWith('/data-quality'))return {ok:false,status:404};
    return {ok:true,json:async()=>route.endsWith('/login')?{token:'test-token'}:route.endsWith('/me')?{role:'ADMIN'}:route==='/api/farms'?[{ID:'farm-test'}]:{}};
  };
  await assert.rejects(audit({FARM_AUDIT_PASSWORD:'test'},fake),/HTTP_404/);assert(logout);
});
test('insecure remote and credentials embedded in URLs are rejected before requesting',async()=>{
  for(const url of ['http://example.com','http://user:pass@localhost','https://example.com/?token=x'])
    await assert.rejects(audit({FARM_AUDIT_BASE_URL:url,FARM_AUDIT_PASSWORD:'test'},()=>assert.fail()),/INVALID_API_BASE/);
});
