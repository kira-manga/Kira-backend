-- Bounded keyset enumeration of one writer's immutable publication keys. The column's existing
-- C collation is retained. All states participate: PREPARED/unknown dispatch and APPLIED must not
-- disappear from cutoff recovery. No row, epoch, evidence, capacity counter or old migration changes.
-- The added logical key tuple is included in the publication lifecycle-envelope qualification;
-- that is not physical disk/MVCC sizing, runtime authority or permission to delete sealed rows.
CREATE INDEX idx_complaint_publication_manifest
    ON complaint_journal_publications (data_scope_id, writer_generation, object_key);
