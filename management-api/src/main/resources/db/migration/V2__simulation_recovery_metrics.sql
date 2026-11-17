-- First-class simulation counters and recovery outcomes. Existing JSON reports remain
-- the complete source for historical detail; nullable columns preserve old rows.
ALTER TABLE simulation_runs ADD COLUMN requests_succeeded BIGINT;
ALTER TABLE simulation_runs ADD COLUMN requests_failed BIGINT;
ALTER TABLE simulation_runs ADD COLUMN fallback_requests BIGINT;
ALTER TABLE simulation_runs ADD COLUMN recovery_outcome VARCHAR(24);
ALTER TABLE simulation_runs ADD COLUMN original_primary_owners_json VARCHAR(100000);
ALTER TABLE simulation_runs ADD COLUMN promoted_replicas_json VARCHAR(100000);
