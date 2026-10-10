CREATE TABLE IF NOT EXISTS tenants (
 id VARCHAR(36) PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE,
 name VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS members (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
 username VARCHAR(60) NOT NULL, display_name VARCHAR(80) NOT NULL,
 password_hash VARCHAR(100) NOT NULL, role VARCHAR(30) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(tenant_id, username),
 CHECK(role IN ('PLATFORM_ADMIN','ADMIN','OPERATOR','VIEWER'))
);
CREATE TABLE IF NOT EXISTS sessions (
 token_hash VARCHAR(64) PRIMARY KEY, member_id VARCHAR(36) NOT NULL REFERENCES members(id),
 expires_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS farms (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
 name VARCHAR(100) NOT NULL, description VARCHAR(500) NOT NULL,
 UNIQUE(tenant_id, id), UNIQUE(tenant_id, name)
);
CREATE TABLE IF NOT EXISTS plots (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, farm_id VARCHAR(36) NOT NULL,
 name VARCHAR(100) NOT NULL, area_mu DECIMAL(12,2) NOT NULL CHECK(area_mu>0),
 crop VARCHAR(80) NOT NULL, UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id), UNIQUE(tenant_id,farm_id,name)
);
CREATE TABLE IF NOT EXISTS plantings (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, plot_id VARCHAR(36) NOT NULL,
 crop VARCHAR(80) NOT NULL, variety VARCHAR(80) NOT NULL, area_mu DECIMAL(12,2) NOT NULL CHECK(area_mu>0),
 start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(20) NOT NULL,
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),
 CHECK(end_date>=start_date), CHECK(status IN ('PLANNED','ACTIVE','FINISHED'))
);
CREATE TABLE IF NOT EXISTS farm_tasks (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, plot_id VARCHAR(36) NOT NULL,
 title VARCHAR(120) NOT NULL, task_type VARCHAR(30) NOT NULL, due_date DATE NOT NULL,
 status VARCHAR(20) NOT NULL, note VARCHAR(500) NOT NULL,
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),
 CHECK(status IN ('PENDING','RUNNING','COMPLETED','CANCELLED'))
);
CREATE TABLE IF NOT EXISTS production (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, plot_id VARCHAR(36) NOT NULL,
 record_date DATE NOT NULL, yield_kg DECIMAL(14,2) NOT NULL CHECK(yield_kg>0), note VARCHAR(500) NOT NULL,
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS devices (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, farm_id VARCHAR(36) NOT NULL,
 name VARCHAR(100) NOT NULL, metric VARCHAR(30) NOT NULL, unit VARCHAR(20) NOT NULL,
 adapter VARCHAR(20) NOT NULL, UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id),
 CHECK(adapter IN ('MANUAL','SIMULATED'))
);
CREATE TABLE IF NOT EXISTS observations (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL,
 measured_value DECIMAL(14,3) NOT NULL, measured_at TIMESTAMP NOT NULL, source VARCHAR(20) NOT NULL,
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id),
 CHECK(source IN ('MANUAL','SIMULATED'))
);
CREATE TABLE IF NOT EXISTS audit_events (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
 actor VARCHAR(60) NOT NULL, action VARCHAR(60) NOT NULL,
 resource_id VARCHAR(36) NOT NULL, occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_plots_tenant ON plots(tenant_id);
CREATE INDEX IF NOT EXISTS ix_tasks_tenant ON farm_tasks(tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_observations_tenant ON observations(tenant_id, device_id, measured_at);
CREATE INDEX IF NOT EXISTS ix_audit_tenant ON audit_events(tenant_id, occurred_at);

-- Additive workspace schema: existing 0.1.0 data and primary-point APIs remain valid.
CREATE TABLE IF NOT EXISTS farm_profiles (
 tenant_id VARCHAR(36) NOT NULL, farm_id VARCHAR(36) NOT NULL, region VARCHAR(120) NOT NULL DEFAULT '',
 farm_type VARCHAR(30) NOT NULL DEFAULT 'FIELD', demo BOOLEAN NOT NULL DEFAULT FALSE,
 layout_revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(tenant_id,farm_id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS plot_shapes (
 tenant_id VARCHAR(36) NOT NULL, plot_id VARCHAR(36) NOT NULL, boundary_json VARCHAR(16000) NOT NULL,
 PRIMARY KEY(tenant_id,plot_id),
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS asset_profiles (
 tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL, code VARCHAR(60) NOT NULL,
 device_type VARCHAR(30) NOT NULL, protocol VARCHAR(30) NOT NULL, lifecycle VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 plot_id VARCHAR(36), plan_x DECIMAL(10,3), plan_y DECIMAL(10,3),
 model VARCHAR(100) NOT NULL DEFAULT '', notes VARCHAR(500) NOT NULL DEFAULT '',
 interval_seconds INTEGER NOT NULL DEFAULT 900, last_received_at TIMESTAMP WITH TIME ZONE,
 credential_hash VARCHAR(64), revision INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,device_id), UNIQUE(tenant_id,code), UNIQUE(credential_hash),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),
 CHECK(protocol IN ('MANUAL','SIMULATED','HTTP_PUSH')),
 CHECK(lifecycle IN ('ACTIVE','MAINTENANCE','DISABLED')),
 CHECK((plan_x IS NULL AND plan_y IS NULL) OR
       (plan_x IS NOT NULL AND plan_y IS NOT NULL AND plan_x BETWEEN 0 AND 1000 AND plan_y BETWEEN 0 AND 700)),
 CHECK(interval_seconds BETWEEN 30 AND 86400)
);
CREATE TABLE IF NOT EXISTS device_channels (
 tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL, metric VARCHAR(40) NOT NULL,
 lower_limit DECIMAL(16,3), upper_limit DECIMAL(16,3),
 PRIMARY KEY(tenant_id,device_id,metric),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id) ON DELETE CASCADE,
 CHECK(lower_limit IS NULL OR upper_limit IS NULL OR lower_limit < upper_limit)
);
CREATE TABLE IF NOT EXISTS telemetry_readings (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL,
 metric VARCHAR(40) NOT NULL, measured_value DECIMAL(16,3) NOT NULL,
 measured_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 source VARCHAR(20) NOT NULL,
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id),
 CHECK(source IN ('MANUAL','SIMULATED','HTTP_PUSH'))
);
CREATE TABLE IF NOT EXISTS ingestion_batches (
 tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL, message_id VARCHAR(80) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,device_id,message_id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS device_alerts (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL,
 metric VARCHAR(40) NOT NULL, measured_value DECIMAL(16,3) NOT NULL, message VARCHAR(300) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN', opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL, handled_by VARCHAR(80), handle_note VARCHAR(300),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id),
 CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED'))
);
CREATE TABLE IF NOT EXISTS demo_scenarios (
 tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id), scenario VARCHAR(40) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(tenant_id,scenario)
);
CREATE INDEX IF NOT EXISTS ix_telemetry_history ON telemetry_readings(tenant_id,device_id,metric,measured_at);
CREATE INDEX IF NOT EXISTS ix_alert_status ON device_alerts(tenant_id,status,opened_at);

CREATE TABLE IF NOT EXISTS member_preferences (
 member_id VARCHAR(36) PRIMARY KEY REFERENCES members(id) ON DELETE CASCADE,
 avatar_data CLOB, theme_mode VARCHAR(12) NOT NULL DEFAULT 'SYSTEM',
 accent VARCHAR(12) NOT NULL DEFAULT 'FOREST', must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK(theme_mode IN ('LIGHT','DARK','SYSTEM')), CHECK(accent IN ('FOREST','BLUE','AMBER','ROSE'))
);

CREATE TABLE IF NOT EXISTS farm_georeference (
 tenant_id VARCHAR(36) NOT NULL, farm_id VARCHAR(36) NOT NULL,
 mode VARCHAR(16) NOT NULL, latitude DOUBLE PRECISION NOT NULL, longitude DOUBLE PRECISION NOT NULL,
 width_meters INTEGER NOT NULL, height_meters INTEGER NOT NULL, location_label VARCHAR(120) NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(tenant_id,farm_id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id) ON DELETE CASCADE,
 CHECK(mode IN ('SATELLITE','STREET','PLAN')), CHECK(latitude BETWEEN -80 AND 80),
 CHECK(longitude BETWEEN -180 AND 180), CHECK(width_meters BETWEEN 100 AND 20000), CHECK(height_meters BETWEEN 100 AND 20000)
);

-- Additive field workflow; old tenant accounts and business rows remain intact.
ALTER TABLE members ADD CONSTRAINT IF NOT EXISTS uq_member_tenant UNIQUE(tenant_id,id);
ALTER TABLE farm_tasks ADD CONSTRAINT IF NOT EXISTS uq_task_tenant UNIQUE(tenant_id,id);
CREATE TABLE IF NOT EXISTS task_fieldwork (
 tenant_id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL, assignee_id VARCHAR(36),
 method VARCHAR(20) NOT NULL, blocked_reason VARCHAR(500) NOT NULL DEFAULT '',
 completion_note VARCHAR(500) NOT NULL DEFAULT '', actual_area_mu DECIMAL(12,2) NOT NULL DEFAULT 0,
 completed_at TIMESTAMP, PRIMARY KEY(tenant_id,task_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES farm_tasks(tenant_id,id),
 FOREIGN KEY(tenant_id,assignee_id) REFERENCES members(tenant_id,id),
 CHECK(method IN ('UNCONFIRMED','DRONE','MANUAL','SERVICE'))
);
CREATE TABLE IF NOT EXISTS field_issues (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, plot_id VARCHAR(36) NOT NULL,
 category VARCHAR(20) NOT NULL, severity VARCHAR(12) NOT NULL, description VARCHAR(500) NOT NULL,
 reporter_id VARCHAR(36) NOT NULL, status VARCHAR(16) NOT NULL, task_id VARCHAR(36),
 created_at TIMESTAMP NOT NULL, review_note VARCHAR(500),reviewed_by VARCHAR(36),resolved_at TIMESTAMP,
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),
 FOREIGN KEY(tenant_id,reporter_id) REFERENCES members(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewed_by) REFERENCES members(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES farm_tasks(tenant_id,id),
 CHECK(status IN ('OPEN','ASSIGNED','RESOLVED')), CHECK(category IN ('PEST','WATER','EQUIPMENT','OTHER')),
 CHECK(severity IN ('NORMAL','HIGH'))
);
CREATE TABLE IF NOT EXISTS field_work_logs (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,task_id VARCHAR(36) NOT NULL,
 actor_id VARCHAR(36) NOT NULL,action VARCHAR(20) NOT NULL,note VARCHAR(500) NOT NULL,
 method VARCHAR(20) NOT NULL,actual_area_mu DECIMAL(12,2) NOT NULL,occurred_at TIMESTAMP NOT NULL,
 FOREIGN KEY(tenant_id,task_id) REFERENCES farm_tasks(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES members(tenant_id,id)
);
CREATE INDEX IF NOT EXISTS ix_field_issues_plot ON field_issues(tenant_id,plot_id,status);
CREATE INDEX IF NOT EXISTS ix_field_logs_task ON field_work_logs(tenant_id,task_id,occurred_at);

-- Additive calibration: no customer locations, credentials or device inventory are embedded.
ALTER TABLE asset_profiles ADD COLUMN IF NOT EXISTS location_mode VARCHAR(16) NOT NULL DEFAULT 'LOCAL_PLAN';
ALTER TABLE asset_profiles ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE asset_profiles ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE asset_profiles ADD COLUMN IF NOT EXISTS control_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE asset_profiles ADD CONSTRAINT IF NOT EXISTS ck_asset_location CHECK(
 (location_mode='LOCAL_PLAN' AND latitude IS NULL AND longitude IS NULL) OR
 (location_mode='WGS84' AND plan_x IS NULL AND plan_y IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
  AND latitude BETWEEN -80 AND 80 AND longitude BETWEEN -180 AND 180));

CREATE TABLE IF NOT EXISTS device_commands (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(80) NOT NULL, action VARCHAR(40) NOT NULL, command_value DECIMAL(16,3),
 protocol VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL, actor VARCHAR(60) NOT NULL,
 note VARCHAR(300) NOT NULL, result_note VARCHAR(300), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, dispatched_at TIMESTAMP WITH TIME ZONE,
 finished_at TIMESTAMP WITH TIME ZONE, receipt_hash VARCHAR(64),
 UNIQUE(tenant_id,device_id,request_id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id),
 CHECK(status IN ('PENDING','DISPATCHED','SUCCEEDED','FAILED','EXPIRED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS ix_commands_device ON device_commands(tenant_id,device_id,created_at);

-- Private per-member assistant history; irrigation decisions are separate audited records.
CREATE TABLE IF NOT EXISTS ai_conversations (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,member_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,
 title VARCHAR(80) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,member_id) REFERENCES members(tenant_id,id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id)
);
-- 阶段 F：会话列表与自动标题。pinned_at 为空表示未置顶；title_source 区分模型/回退自动生成的标题
-- （'auto'）与用户手动重命名过的标题（'user'）——后端据此永不用自动标题覆盖用户已重命名的会话。
ALTER TABLE ai_conversations ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ai_conversations ADD COLUMN IF NOT EXISTS title_source VARCHAR(10) NOT NULL DEFAULT 'auto';
ALTER TABLE ai_conversations ADD CONSTRAINT IF NOT EXISTS chk_ai_conversations_title_source CHECK(title_source IN ('auto','user'));
CREATE TABLE IF NOT EXISTS ai_messages (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,conversation_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(80) NOT NULL,role VARCHAR(16) NOT NULL,content VARCHAR(16000) NOT NULL,mode VARCHAR(20) NOT NULL,
 diagnostic VARCHAR(40) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,conversation_id,request_id,role),
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES ai_conversations(tenant_id,id) ON DELETE CASCADE,
 CHECK(role IN ('user','assistant'))
);
ALTER TABLE ai_messages ADD CONSTRAINT IF NOT EXISTS uq_ai_messages_tenant UNIQUE(tenant_id,id);
-- Safe, already-completed activity summaries only; no raw prompts, tool args or credentials.
CREATE TABLE IF NOT EXISTS ai_message_activities (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,conversation_id VARCHAR(36) NOT NULL,
 message_id VARCHAR(36) NOT NULL,activity_id VARCHAR(80) NOT NULL,sequence_no INTEGER NOT NULL,
 kind VARCHAR(20) NOT NULL,label VARCHAR(120) NOT NULL,status VARCHAR(20) NOT NULL,
 detail VARCHAR(500),result_summary VARCHAR(500),
 started_at TIMESTAMP WITH TIME ZONE,finished_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(tenant_id,message_id,activity_id),
 FOREIGN KEY(tenant_id,message_id) REFERENCES ai_messages(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,conversation_id) REFERENCES ai_conversations(tenant_id,id) ON DELETE CASCADE,
 CHECK(kind IN ('context','tool','source','approval','task')),
 CHECK(status IN ('pending','running','completed','error'))
);
CREATE INDEX IF NOT EXISTS ix_ai_activities_message ON ai_message_activities(tenant_id,message_id,sequence_no);
CREATE TABLE IF NOT EXISTS ai_irrigation_policies (
 tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,plot_id VARCHAR(36) NOT NULL,
 sensor_id VARCHAR(36) NOT NULL,pump_id VARCHAR(36) NOT NULL,mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
 threshold_value DECIMAL(8,2) NOT NULL,duration_seconds INTEGER NOT NULL,cooldown_minutes INTEGER NOT NULL,
 daily_limit INTEGER NOT NULL,owner_id VARCHAR(36) NOT NULL,revision INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,last_check_at TIMESTAMP WITH TIME ZONE,last_result VARCHAR(300) NOT NULL DEFAULT '',
 PRIMARY KEY(tenant_id,plot_id),FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id),
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),FOREIGN KEY(tenant_id,sensor_id) REFERENCES devices(tenant_id,id),
 FOREIGN KEY(tenant_id,pump_id) REFERENCES devices(tenant_id,id),FOREIGN KEY(tenant_id,owner_id) REFERENCES members(tenant_id,id),
 CHECK(mode IN ('MANUAL','AUTO')),CHECK(threshold_value BETWEEN 5 AND 80),CHECK(duration_seconds BETWEEN 10 AND 300),
 CHECK(cooldown_minutes BETWEEN 30 AND 1440),CHECK(daily_limit BETWEEN 1 AND 6)
);
CREATE TABLE IF NOT EXISTS ai_irrigation_runs (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,plot_id VARCHAR(36) NOT NULL,
 sensor_id VARCHAR(36) NOT NULL,pump_id VARCHAR(36) NOT NULL,policy_revision INTEGER NOT NULL,
 status VARCHAR(20) NOT NULL,reason VARCHAR(500) NOT NULL,duration_seconds INTEGER NOT NULL,
 moisture_value DECIMAL(8,2) NOT NULL,requested_by VARCHAR(60) NOT NULL,approved_by VARCHAR(60),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE,stop_at TIMESTAMP WITH TIME ZONE,finished_at TIMESTAMP WITH TIME ZONE,
 command_id VARCHAR(36),stop_command_id VARCHAR(36),result_note VARCHAR(300) NOT NULL DEFAULT '',
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id),FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),
 FOREIGN KEY(tenant_id,sensor_id) REFERENCES devices(tenant_id,id),FOREIGN KEY(tenant_id,pump_id) REFERENCES devices(tenant_id,id),
 CHECK(status IN ('PROPOSED','RUNNING','COMPLETED','CANCELLED','EXPIRED'))
);
CREATE INDEX IF NOT EXISTS ix_ai_runs ON ai_irrigation_runs(tenant_id,pump_id,status,created_at);

-- Opt-in synthetic operating scenario. The registry never includes ordinary farms.
CREATE TABLE IF NOT EXISTS demo_operating_farms (
 tenant_id VARCHAR(36) NOT NULL, farm_id VARCHAR(36) NOT NULL, seeded_on DATE NOT NULL,
 last_daily_date DATE NOT NULL, PRIMARY KEY(tenant_id,farm_id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id) ON DELETE CASCADE
);

-- Research fixture provenance and explicit harvest lineage; never authentic field evidence.
ALTER TABLE plantings ADD CONSTRAINT IF NOT EXISTS uq_planting_tenant UNIQUE(tenant_id,id);
ALTER TABLE production ADD CONSTRAINT IF NOT EXISTS uq_production_tenant UNIQUE(tenant_id,id);
CREATE TABLE IF NOT EXISTS production_lineage (
 tenant_id VARCHAR(36) NOT NULL, production_id VARCHAR(36) NOT NULL,
 planting_id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,production_id),
 FOREIGN KEY(tenant_id,production_id) REFERENCES production(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,planting_id) REFERENCES plantings(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES farm_tasks(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS demo_research_farms (
 tenant_id VARCHAR(36) NOT NULL, farm_id VARCHAR(36) NOT NULL, version INTEGER NOT NULL,
 history_start DATE NOT NULL, as_of_date DATE NOT NULL, generated_at TIMESTAMP NOT NULL,
 PRIMARY KEY(tenant_id,farm_id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id) ON DELETE CASCADE
);

-- Bind a vendor's device identity to one authenticated tenant device.
CREATE TABLE IF NOT EXISTS device_integrations (
 tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL,
 adapter_type VARCHAR(20) NOT NULL, external_id VARCHAR(100) NOT NULL,
 upstream_topic VARCHAR(240) NOT NULL DEFAULT '', downstream_topic VARCHAR(240) NOT NULL DEFAULT '',
 bindings_json VARCHAR(12000) NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,device_id), UNIQUE(tenant_id,adapter_type,external_id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id),
 CHECK(adapter_type IN ('PUMP_MQTT','GATE_MQTT','LAN_DTU'))
);
CREATE TABLE IF NOT EXISTS device_ingest_events (
 tenant_id VARCHAR(36) NOT NULL, device_id VARCHAR(36) NOT NULL, message_id VARCHAR(80) NOT NULL,
 adapter_type VARCHAR(20) NOT NULL, payload_hash VARCHAR(64) NOT NULL, mapping_revision INTEGER NOT NULL,
 reading_count INTEGER NOT NULL, missing_json VARCHAR(4000) NOT NULL,
 measured_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,device_id,message_id),
 FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id)
);
CREATE INDEX IF NOT EXISTS ix_ingest_events ON device_ingest_events(tenant_id,device_id,received_at);

ALTER TABLE device_alerts ADD CONSTRAINT IF NOT EXISTS uq_alert_tenant UNIQUE(tenant_id,id);
ALTER TABLE field_issues ADD CONSTRAINT IF NOT EXISTS uq_issue_tenant UNIQUE(tenant_id,id);
CREATE TABLE IF NOT EXISTS alert_field_issues (
 tenant_id VARCHAR(36) NOT NULL, alert_id VARCHAR(36) NOT NULL, issue_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,alert_id), UNIQUE(tenant_id,issue_id),
 FOREIGN KEY(tenant_id,alert_id) REFERENCES device_alerts(tenant_id,id),
 FOREIGN KEY(tenant_id,issue_id) REFERENCES field_issues(tenant_id,id)
);

-- Camera media belongs to the tenant-scoped asset; source URLs are runtime data.
CREATE TABLE IF NOT EXISTS camera_profiles (
 tenant_id VARCHAR(36) NOT NULL,device_id VARCHAR(36) NOT NULL,
 media_mode VARCHAR(20) NOT NULL,demo_scene VARCHAR(30) NOT NULL DEFAULT 'qinghe',
 source_url VARCHAR(2048) NOT NULL DEFAULT '',view_label VARCHAR(100) NOT NULL DEFAULT '',
 PRIMARY KEY(tenant_id,device_id),FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id) ON DELETE CASCADE,
 CHECK(media_mode IN ('NONE','DEMO_IMAGE','IMAGE','VIDEO'))
);

-- Geographic map subdivisions preserve the parent plot's existing business history.
CREATE TABLE IF NOT EXISTS farm_map_parcels (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,plot_id VARCHAR(36) NOT NULL,
 name VARCHAR(80) NOT NULL,boundary_json CLOB NOT NULL,source VARCHAR(24) NOT NULL,source_note VARCHAR(500) NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0,created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id),
 FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS farm_map_zones (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,parcel_id VARCHAR(36) NOT NULL,
 pump_id VARCHAR(36) NOT NULL,name VARCHAR(80) NOT NULL,pipeline_json CLOB NOT NULL,nodes_json CLOB NOT NULL,
 source VARCHAR(24) NOT NULL,UNIQUE(tenant_id,id),UNIQUE(tenant_id,parcel_id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id),FOREIGN KEY(tenant_id,parcel_id) REFERENCES farm_map_parcels(tenant_id,id),
 FOREIGN KEY(tenant_id,pump_id) REFERENCES devices(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS farm_map_jobs (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,plot_id VARCHAR(36) NOT NULL,
 parcel_id VARCHAR(36) NOT NULL,device_id VARCHAR(36) NOT NULL,task_id VARCHAR(36),owner_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(80) NOT NULL,kind VARCHAR(20) NOT NULL,title VARCHAR(120) NOT NULL,status VARCHAR(20) NOT NULL,
 parameters_json CLOB NOT NULL,route_json CLOB NOT NULL,boundary_json CLOB NOT NULL,
 duration_seconds INTEGER NOT NULL,elapsed_seconds DECIMAL(12,3) NOT NULL DEFAULT 0,
 estimated_m3 DECIMAL(14,6) NOT NULL DEFAULT 0,flow_m3h DECIMAL(12,3),measured_m3 DECIMAL(14,6),
 start_command_id VARCHAR(36),stop_command_id VARCHAR(36),result_note VARCHAR(500) NOT NULL DEFAULT '',
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,started_at TIMESTAMP WITH TIME ZONE,last_tick_at TIMESTAMP WITH TIME ZONE,finished_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id),FOREIGN KEY(tenant_id,plot_id) REFERENCES plots(tenant_id,id),
 FOREIGN KEY(tenant_id,parcel_id) REFERENCES farm_map_parcels(tenant_id,id),FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES farm_tasks(tenant_id,id),FOREIGN KEY(tenant_id,owner_id) REFERENCES members(tenant_id,id),
 CHECK(kind IN ('MACHINERY','IRRIGATION')),CHECK(status IN ('RUNNING','PAUSED','COMPLETED','STOPPED','FAILED')),
 CHECK(duration_seconds BETWEEN 10 AND 300),CHECK(estimated_m3>=0)
);
CREATE INDEX IF NOT EXISTS ix_farm_map_jobs ON farm_map_jobs(tenant_id,farm_id,created_at);

-- Reversible portfolio migration; archived farms retain all business records.
CREATE TABLE IF NOT EXISTS farm_archives (
 tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,reason VARCHAR(300) NOT NULL,
 archived_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(tenant_id,farm_id),FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS demo_portfolio_farms (
 tenant_id VARCHAR(36) NOT NULL,farm_id VARCHAR(36) NOT NULL,fixture_key VARCHAR(30) NOT NULL,
 PRIMARY KEY(tenant_id,farm_id),UNIQUE(tenant_id,fixture_key),
 FOREIGN KEY(tenant_id,farm_id) REFERENCES farms(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS machinery_profiles (
 tenant_id VARCHAR(36) NOT NULL,device_id VARCHAR(36) NOT NULL,profile_json CLOB NOT NULL,
 PRIMARY KEY(tenant_id,device_id),FOREIGN KEY(tenant_id,device_id) REFERENCES devices(tenant_id,id) ON DELETE CASCADE
);
-- An immutable accepted plan owns the clock and model snapshot. Legacy map jobs remain readable.
CREATE TABLE IF NOT EXISTS machinery_job_plans (
 tenant_id VARCHAR(36) NOT NULL,job_id VARCHAR(36) NOT NULL,snapshot_json CLOB NOT NULL,
 simulation_seconds INTEGER NOT NULL,PRIMARY KEY(tenant_id,job_id),
 FOREIGN KEY(tenant_id,job_id) REFERENCES farm_map_jobs(tenant_id,id),CHECK(simulation_seconds BETWEEN 1 AND 86400)
);
CREATE TABLE IF NOT EXISTS farm_map_job_events (
 id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,job_id VARCHAR(36) NOT NULL,
 action VARCHAR(30) NOT NULL,note VARCHAR(500) NOT NULL,occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 FOREIGN KEY(tenant_id,job_id) REFERENCES farm_map_jobs(tenant_id,id)
);
CREATE INDEX IF NOT EXISTS ix_map_job_events ON farm_map_job_events(tenant_id,job_id,occurred_at);
