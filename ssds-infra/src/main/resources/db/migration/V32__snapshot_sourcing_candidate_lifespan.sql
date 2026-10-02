ALTER TABLE sourcing_candidate
    ADD COLUMN estimated_lifespan_days INTEGER;

UPDATE sourcing_candidate
SET estimated_lifespan_days = time_gap_days + lead_time_days
WHERE time_gap_days IS NOT NULL;

COMMENT ON COLUMN sourcing_candidate.estimated_lifespan_days IS
    '尋源狀態最後一次可更新時的剩餘壽命快照；淘汰後凍結';
