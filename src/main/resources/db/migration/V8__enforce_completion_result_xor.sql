-- A terminal completion carries exactly one of result (success) or error (failure).
-- V5 prohibited both being populated but accidentally allowed both to be NULL.
ALTER TABLE completion_results DROP CONSTRAINT chk_result_xor_error;
ALTER TABLE completion_results
    ADD CONSTRAINT chk_result_xor_error CHECK ((result IS NOT NULL) <> (error IS NOT NULL));
