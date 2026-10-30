-- Keyword search over job-description passages, alongside the search by meaning.
--
-- The vector index (V12) finds passages close in meaning to a question. This one finds
-- passages that contain the question's words, with PostgreSQL's built-in full-text search.
-- to_tsvector reduces a passage to the stems of its words ("indexing" becomes "index") and
-- leaves out common words such as "the"; a GIN index maps every stem to the passages that
-- hold it, so a search does not have to read every passage.
--
-- PostgreSQL only uses an index on an expression when a query writes that same
-- expression, so JobDescriptionChunkRepository spells it exactly like this:
-- to_tsvector('english', content).
CREATE INDEX job_description_chunks_content_fts_idx
    ON job_description_chunks USING gin (to_tsvector('english', content));
