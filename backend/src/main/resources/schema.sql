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
