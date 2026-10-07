// Real MySQL acceptance, restricted to the project's isolated localhost instance.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { randomBytes } = require('node:crypto');
const { spawn, execFileSync } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const root = path.resolve(__dirname, '..');
const config = JSON.parse(fs.readFileSync(path.join(root, '.cache/simulation-db.json'), 'utf8').replace(/^\uFEFF/, ''));
assert.match(config.url, /^jdbc:mysql:\/\/127\.0\.0\.1:\d+\//);
const database = 'zhinong_verify_' + randomBytes(6).toString('hex');
const mysql = path.join(config.mysqlHome, 'bin/mysql.exe');
function sql(statement) {
  return execFileSync(mysql, ['--no-defaults', '--host=127.0.0.1', `--port=${config.port}`, '--user=root',
    '--protocol=TCP', '--ssl-mode=REQUIRED', '--default-character-set=utf8mb4', '--batch', '--skip-column-names'], {
    input: statement, encoding: 'utf8', windowsHide: true,
    env: { ...process.env, MYSQL_PWD: config.rootPassword }, maxBuffer: 4 * 1024 * 1024,
  }).trim();
}
function query(statement) { return sql(`USE ${database}; ${statement}`); }

(async () => {
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  sql(`CREATE DATABASE ${database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;`);
  const password = randomBytes(24).toString('base64url');
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(FARM_|SPRING_|MYSQL_PWD)/.test(key)));
  const server = spawn('java', ['-jar', path.join(root, 'backend/target/zhinong-platform-0.3.0.jar'),
    '--spring.profiles.active=mysql', `--server.port=${port}`, '--server.address=127.0.0.1', '--farm.mqtt.enabled=false'], {
    cwd: path.join(root, 'backend'), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: {
      ...env, FARM_BOOTSTRAP_PASSWORD: password, FARM_DATABASE_USER: 'root', FARM_DATABASE_PASSWORD: config.rootPassword,
      FARM_DATABASE_URL: `jdbc:mysql://127.0.0.1:${config.port}/${database}?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&rewriteBatchedStatements=true`,
      FARM_DATABASE_INIT: 'always', FARM_DEMO: 'true', FARM_DEMO_RICH: 'true', FARM_DEMO_STREAM_ENABLED: 'true',
    },
  });
  let logs = '', success = false, processError;
  server.stdout.on('data', data => { logs += data; });
  server.stderr.on('data', data => { logs += data; });
  server.on('error', error => { processError = error; });
  async function api(token, method, endpoint, body, status = 200, extra = {}) {
    const response = await fetch(`http://127.0.0.1:${port}/api${endpoint}`, {
      method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000),
    });
    const value = await response.json();
    assert.equal(response.status, status, `${method} ${endpoint}: ${JSON.stringify(value)}`);
    return value;
  }
  try {
    for (let n = 0; n < 360 && !logs.includes('Synthetic demo history ready'); n++) {
      if (processError) throw processError;
      assert.equal(server.exitCode, null, 'MySQL acceptance server exited; see .cache/mysql-acceptance.log');
      await delay(500);
    }
    assert.ok(logs.includes('Synthetic demo history ready'), 'MySQL startup timed out');
    const login = async (tenantCode, username) => (await api(null, 'POST', '/auth/login', {tenantCode, username, password})).token;
    const admin = await login('demo-a', 'admin');
    const other = await login('demo-b', 'admin');
    const viewer = await login('demo-a', 'viewer');
    const platform = await login('platform', 'platform');
    const farms = await api(admin, 'GET', '/farm-workspaces');
    assert.equal(farms.length, 4);
    assert.ok(farms.every(f => f.demo === true));
    const assets = await api(admin, 'GET', '/assets');
    assert.equal(assets.length, 27);
    assert.ok(assets.every(a => a.dataQuality === 'COMPLETE'), 'Every fresh synthetic fixture channel must have data');
    const gate = assets.find(a => a.deviceType === 'GATE');
    const pump = assets.find(a => a.deviceType === 'PUMP');
    const soil = assets.find(a => a.code === 'DEMO-CTRL-SOIL');
    for (const farm of farms) {
      await api(admin, 'GET', `/farms/${farm.id}/workspace?days=30`);
      await api(admin, 'GET', `/farms/${farm.id}/operations?hours=720`);
    }
    await api(admin, 'GET', '/dashboard');
    await api(other, 'GET', `/assets/${soil.id}`, undefined, 404);
    await api(platform, 'GET', '/assets', undefined, 403);
    await api(viewer, 'POST', `/assets/${pump.id}/collect`, {}, 403);
    const history = await api(admin, 'GET', `/assets/${soil.id}/history?metric=SOIL_MOISTURE_3&hours=720`);
    assert.ok(history.points.length >= 720);
    assert.ok(history.points.every(p => Number.isFinite(p.value)));
    assert.ok(history.points.every(p => /Z$|[+]00:00$/.test(p.time)), 'MySQL history preserves UTC in API timestamps');
    const coverage = query(`SELECT COUNT(*) FROM (
      SELECT r.tenant_id,r.device_id,r.metric,COUNT(*) n,TIMESTAMPDIFF(SECOND,MIN(r.measured_at),MAX(r.measured_at)) span,p.interval_seconds
      FROM telemetry_readings r JOIN asset_profiles p ON p.tenant_id=r.tenant_id AND p.device_id=r.device_id
      GROUP BY r.tenant_id,r.device_id,r.metric,p.interval_seconds
      HAVING n < 2592000 / p.interval_seconds + 1 OR span < 2592000) q;`);
    assert.equal(coverage, '0', 'All configured synthetic series cover 30 days');
    const holes = query(`SELECT COUNT(*) FROM (
      SELECT r.measured_at,LAG(r.measured_at) OVER(PARTITION BY r.tenant_id,r.device_id,r.metric ORDER BY r.measured_at) previous,p.interval_seconds
      FROM telemetry_readings r JOIN asset_profiles p ON p.tenant_id=r.tenant_id AND p.device_id=r.device_id
    ) q WHERE previous IS NOT NULL AND TIMESTAMPDIFF(SECOND,previous,measured_at)<>interval_seconds;`);
    assert.equal(holes, '0', 'No missing or duplicated slots');
    assert.equal(query("SELECT COUNT(*) FROM telemetry_readings WHERE metric='LIGHT' AND HOUR(DATE_ADD(measured_at,INTERVAL 8 HOUR)) NOT BETWEEN 6 AND 17 AND measured_value<>0;"), '0');
    assert.equal(query(`SELECT COUNT(*) FROM (
      SELECT measured_value,LAG(measured_value) OVER(PARTITION BY tenant_id,device_id,metric ORDER BY measured_at) previous
      FROM telemetry_readings WHERE metric IN ('WATER_TOTAL','ENERGY','RAINFALL')) q WHERE measured_value<previous;`), '0');
    const before = Number(query('SELECT COUNT(*) FROM telemetry_readings;'));
    const gateCommand = await api(admin, 'POST', `/assets/${gate.id}/commands`, {requestId:'mysql-gate-1', action:'SET_OPENING', value:40, note:'Synthetic MySQL acceptance'});
    assert.equal(gateCommand.status, 'SUCCEEDED');
    await api(admin, 'POST', `/assets/${pump.id}/commands`, {requestId:'mysql-pump-1',action:'PUMP_START',note:'Synthetic MySQL acceptance'});
    const sampled = await api(admin, 'POST', `/assets/${pump.id}/collect`, {});
    assert.equal(sampled.channels.find(c => c.metric === 'PUMP_RUNNING').latest.value, 1);
    assert.ok(sampled.channels.find(c => c.metric === 'FLOW').latest.value > 0);
    await api(admin, 'POST', `/assets/${pump.id}/commands`, {requestId:'mysql-pump-stop',action:'PUMP_STOP',note:'Synthetic MySQL acceptance'});
    const stopped = await api(admin, 'POST', `/assets/${pump.id}/collect`, {});
    assert.equal(stopped.channels.find(c => c.metric === 'FLOW').latest.value, 0);
    const catalog = await api(admin, 'GET', '/assets/catalog');
    const native = await api(admin, 'POST', '/assets', {
      farmId:pump.farmId, plotId:pump.plotId, name:'Synthetic native MySQL pump', code:'MYSQL-NATIVE-PUMP',
      deviceType:'PUMP', protocol:'HTTP_PUSH', lifecycle:'ACTIVE', model:'fixture', notes:'Synthetic acceptance only',
      intervalSeconds:60, revision:0, controlEnabled:true,
      channels:catalog.presets.find(p => p.deviceType === 'PUMP').metrics.map(metric => metric === 'WATER_LEVEL' ? {metric,lowerLimit:10} : {metric}),
    });
    const key = (await api(admin, 'POST', `/assets/${native.id}/credentials`, {})).key;
    const integration = await api(admin, 'GET', `/assets/${native.id}/integration`);
    const binding = {adapterType:'PUMP_MQTT',externalId:'synthetic-mysql-pump',upstreamTopic:'/fixture/mysql/up',downstreamTopic:'/fixture/mysql/down',revision:0,
      bindings:integration.adapters.find(a => a.code === 'PUMP_MQTT').bindings};
    await api(admin, 'PUT', `/assets/${native.id}/integration`, binding);
    const conflicting = await api(admin,'POST','/assets',{
      farmId:pump.farmId, name:'Duplicate identity fixture', code:'MYSQL-NATIVE-OTHER', deviceType:'PUMP',protocol:'HTTP_PUSH',lifecycle:'ACTIVE',
      model:'fixture',notes:'Synthetic identity conflict',intervalSeconds:60,revision:0,channels:catalog.presets.find(p=>p.deviceType==='PUMP').metrics.map(metric=>({metric}))});
    await api(admin,'PUT',`/assets/${conflicting.id}/integration`,{...binding,downstreamTopic:'/fixture/incorrect/down'},409);
    assert.equal((await api(admin,'GET',`/assets/${native.id}/integration`)).config.downstreamTopic,binding.downstreamTopic);
    const packet = {messageId:'mysql-native-1',externalId:binding.externalId,topic:binding.upstreamTopic,measuredAt:new Date(Date.now()-1000).toISOString(),
      payload:{params:{dir:'up',r_data:Object.entries({mainPumpRunning:0,remoteFlag:1,emergencyStop:0,mainPumpFreqSet:35,mainPumpVoltage:380,waterLevelFeedback:5})
        .map(([name,value])=>({name,value}))}}};
    const header = {'X-Device-Key':key};
    assert.equal((await api(null,'POST','/ingest/smart-farm',packet,200,header)).count,6);
    assert.equal((await api(null,'POST','/ingest/smart-farm',packet,200,header)).duplicate,true);
    const nativeCommand = await api(admin,'POST',`/assets/${native.id}/commands`,{requestId:'mysql-native-start',action:'PUMP_START',note:'Synthetic receipt acceptance'});
    const polled = (await api(null,'POST','/ingest/smart-farm/commands/poll',{},200,header)).commands[0];
    assert.ok(/Z$|[+]00:00$/.test(polled.expiresAt));
    assert.ok(Date.parse(polled.expiresAt)>Date.now() && Date.parse(polled.expiresAt)<Date.now()+121000);
    const receipt = {externalId:binding.externalId,topic:binding.upstreamTopic,payload:{rw_prot:{dir:'up',id:nativeCommand.id,w_data:[{name:'mainPumpManual',value:'1'}]}}};
    await api(null,'POST',`/ingest/smart-farm/commands/${nativeCommand.id}/receipt`,receipt,200,header);
    const actual = await api(admin,'GET',`/assets/${native.id}`);
    assert.equal(actual.channels.find(c=>c.metric==='PUMP_RUNNING').latest.value,0,'Write receipt never invents running feedback');
    const alert=actual.alerts.find(a=>a.metric==='WATER_LEVEL');
    assert.ok(alert);
    const issue=await api(admin,'POST',`/field-work/alerts/${alert.id}/issue`,{severity:'HIGH',note:'Synthetic MySQL field issue'});
    assert.equal((await api(admin,'POST',`/field-work/alerts/${alert.id}/issue`,{severity:'HIGH',note:'Retry synthetic issue'})).id,issue.id);
    await api(other,'GET',`/assets/${native.id}/integration`,undefined,404);
    const simCatalog=await api(admin,'GET','/simulations/catalog');
    assert.equal(simCatalog.storage.engine,'MySQL');
    assert.equal(simCatalog.storage.separateDatabase,false);
    const simulation=await api(admin,'POST','/simulations',{
      farmId:pump.farmId,label:'Synthetic MySQL quarter',startDate:'2025-05-01',days:90,initialAge:20,drones:1,
      droneCapacity:80,manualCapacity:12,inspectionInterval:3,responseDays:2,outbreakDay:25,outageDays:45,
      severity:0.65,irrigationM3:150,fertilizerCoverage:0.9,cropModels:{},
    });
    await api(viewer,'GET',`/simulations/${simulation.id}`);
    await api(other,'GET',`/simulations/${simulation.id}`,undefined,404);
    const savedSimulation=(await api(admin,'GET','/simulations')).find(s=>s.id===simulation.id);
    assert.ok(Math.abs(Date.parse(savedSimulation.createdAt)-Date.now())<60000);
    await api(admin, 'GET', '/ai/status');
    console.log(JSON.stringify({mysqlVersion:sql('SELECT VERSION();'),syntheticReadings:before,tenantDevices:assets.length,coverageDays:30,missingSlots:0,duplicateSlots:0,nightLightErrors:0,counterRegressions:0,apiAndTenantChecks:'passed'}));
    success = true;
  } finally {
    if (server.pid && server.exitCode === null) {
      if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(server.pid), '/T', '/F'], {windowsHide:true,stdio:'ignore'});
      else server.kill('SIGTERM');
    }
    fs.writeFileSync(path.join(root,'.cache/mysql-acceptance.log'), logs.replaceAll(password,'[redacted]').replaceAll(config.rootPassword,'[redacted]'));
    if (success) sql(`DROP DATABASE ${database};`); // Only this run's random database on localhost.
    else console.error('Acceptance failed; isolated local database retained for diagnosis:', database);
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
