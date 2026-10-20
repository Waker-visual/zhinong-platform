/* Read-only business reconciliation. Reports stay in ignored local cache; no credentials are saved. */
const fs=require('node:fs');
const path=require('node:path');

async function audit(env=process.env,fetchApi=globalThis.fetch) {
  const base=new URL(env.FARM_AUDIT_BASE_URL||'http://127.0.0.1:9175');
  if(base.username||base.password||base.search||base.hash||!['','/'].includes(base.pathname)||
    !['http:','https:'].includes(base.protocol)||
    (base.protocol!=='https:'&&!['127.0.0.1','localhost','[::1]'].includes(base.hostname))) throw new Error('INVALID_API_BASE');
  let password=env.FARM_AUDIT_PASSWORD||'';
  if(!password&&env.FARM_AUDIT_PASSWORD_FILE)password=fs.readFileSync(env.FARM_AUDIT_PASSWORD_FILE,'utf8').trim();
  if(!password)throw new Error('MISSING_CREDENTIALS');
  let token='';
  const request=async(route,method='GET',body)=>{
    let response;
    try {response=await fetchApi(base.origin+'/api'+route,{method,redirect:'error',signal:AbortSignal.timeout(30000),
      headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
      body:body===undefined?undefined:JSON.stringify(body)});}
    catch{throw new Error('API_CONNECTION_FAILED');}
    if(!response.ok)throw new Error('HTTP_'+response.status);
    return response.json();
  };
  try {
    const login=await request('/auth/login','POST',{tenantCode:env.FARM_AUDIT_TENANT||'demo-a',username:env.FARM_AUDIT_USERNAME||'admin',password});
    if(typeof login.token!=='string'||!login.token)throw new Error('LOGIN_TOKEN_MISSING');token=login.token;
    const actor=await request('/auth/me');if(actor.role!=='ADMIN')throw new Error('ADMIN_REQUIRED');
    const farms=await request('/farms');
    const reports=[];
    for(const farm of farms)reports.push(await request('/farms/'+encodeURIComponent(farm.ID)+'/data-quality'));
    return {schemaVersion:1,mode:'READ_ONLY',checkedAt:new Date().toISOString(),reports};
  } finally {if(token)await request('/auth/logout','POST').catch(()=>{});}
}

if(require.main===module) {
  audit().then(report=>{
    const directory=path.resolve(__dirname,'../.cache/data-audits');fs.mkdirSync(directory,{recursive:true});
    const output=path.join(directory,'data-chain-'+Date.now()+'.json');fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({report:output,farms:report.reports.length,
      errors:report.reports.reduce((s,r)=>s+r.summary.errorCount,0),warnings:report.reports.reduce((s,r)=>s+r.summary.warningCount,0)}));
  }).catch(error=>{console.error(/^(INVALID_API_BASE|MISSING_CREDENTIALS|API_CONNECTION_FAILED|HTTP_\d+|ADMIN_REQUIRED|LOGIN_TOKEN_MISSING)$/.test(error.message)?error.message:'AUDIT_FAILED');process.exitCode=1;});
}
module.exports={audit};
