// Called only by verify-mysql.ps1 against its newly created, disposable local database.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const [mysql, client, database] = process.argv.slice(2);
assert.match(database || '', /^zhinong_test_[a-f0-9]{12}$/);
function sql(input, expectSuccess = true) {
  const result = spawnSync(mysql, [`--defaults-extra-file=${client}`, '--batch', '--skip-column-names', database],
    { input, encoding: 'utf8', windowsHide: true });
  if (expectSuccess) assert.equal(result.status, 0, result.stderr);
  else assert.notEqual(result.status, 0, 'Foreign key must reject an unrelated message');
  return result.stdout.trim();
}
assert.equal(sql('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();'), '0', 'Upgrade test requires an empty disposable database');
const schema = fs.readFileSync('backend/src/main/resources/schema-mysql.sql', 'utf8');
const preamble = schema.slice(0, schema.indexOf('CREATE TABLE IF NOT EXISTS tenants'));
sql(preamble); // Empty installations must not attempt ALTER on absent tables.
function table(name) {
  return schema.match(new RegExp(`CREATE TABLE IF NOT EXISTS ${name} \\([\\s\\S]*?ENGINE=InnoDB[^;]+;`))[0];
}
const oldConversations = table('ai_conversations').replace(/,\n pinned_at[\s\S]*?(?=\n\) ENGINE)/, '');
const oldMessages = table('ai_messages').replace(/,\n CONSTRAINT uq_ai_messages_tenant UNIQUE\(tenant_id,id\)/, '');
assert.ok(!oldConversations.includes('title_source') && !oldMessages.includes('uq_ai_messages_tenant'));
sql(['tenants', 'members', 'farms'].map(table).concat(oldConversations, oldMessages).join('\n'));
sql(`INSERT INTO tenants(id,code,name,enabled) VALUES('upgrade-tenant','upgrade-fixture','Fictional upgrade test',FALSE);
INSERT INTO members(id,tenant_id,username,display_name,password_hash,role,enabled) VALUES('upgrade-member','upgrade-tenant','disabled-fixture','Test','not-a-login-hash','VIEWER',FALSE);
INSERT INTO farms(id,tenant_id,name,description) VALUES('upgrade-farm','upgrade-tenant','Test farm','Fictional');
INSERT INTO ai_conversations(id,tenant_id,member_id,farm_id,title,created_at,updated_at) VALUES('upgrade-chat','upgrade-tenant','upgrade-member','upgrade-farm','Existing title',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO ai_messages(id,tenant_id,conversation_id,request_id,role,content,mode,diagnostic,created_at) VALUES('upgrade-message','upgrade-tenant','upgrade-chat','fixture-request','assistant','Existing answer','RULES','',CURRENT_TIMESTAMP);`);
sql(schema);
assert.equal(sql("SELECT title,title_source,pinned_at IS NULL FROM ai_conversations WHERE id='upgrade-chat';"), 'Existing title\tauto\t1');
assert.equal(sql("SELECT content FROM ai_messages WHERE id='upgrade-message';"), 'Existing answer');
sql("UPDATE ai_conversations SET title='User title',title_source='user',pinned_at=CURRENT_TIMESTAMP WHERE id='upgrade-chat';");
sql(schema); // Repeated initialization preserves history, manual titles and pins.
assert.equal(sql("SELECT title,title_source,pinned_at IS NOT NULL FROM ai_conversations WHERE id='upgrade-chat';"), 'User title\tuser\t1');
const activity = "INSERT INTO ai_message_activities(id,tenant_id,conversation_id,message_id,activity_id,sequence_no,kind,label,status) VALUES('upgrade-activity','upgrade-tenant','upgrade-chat','upgrade-message','test',1,'tool','Test','completed');";
sql(activity.replace("'upgrade-message'", "'missing-message'"), false);
sql(activity);
sql("DELETE FROM ai_conversations WHERE tenant_id='upgrade-tenant' AND id='upgrade-chat';");
assert.equal(sql("SELECT COUNT(*) FROM ai_message_activities WHERE tenant_id='upgrade-tenant';"), '0');
sql("DELETE FROM farms WHERE tenant_id='upgrade-tenant'; DELETE FROM members WHERE tenant_id='upgrade-tenant'; DELETE FROM tenants WHERE id='upgrade-tenant';");
console.log('MySQL AI upgrade: empty install, existing history, repeated startup and activity foreign keys passed');
