-- Bounded retention scans and stale-request recovery for completion prompt/result data.
CREATE INDEX idx_completion_requests_retention
    ON completion_requests (created_at, id)
    WHERE status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED');
