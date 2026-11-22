ALTER TABLE pipeline_execution
    DROP CONSTRAINT pipeline_execution_status_check,
    DROP CONSTRAINT pipeline_execution_check,
    ADD CHECK (status IN ('RUNNING', 'FAILED', 'COMPLETED')),
    ADD CHECK ((status = 'RUNNING') = (finished_at IS NULL));
