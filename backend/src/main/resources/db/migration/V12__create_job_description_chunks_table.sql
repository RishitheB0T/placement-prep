-- Job-description PDFs, split into chunks and stored with their embeddings, for
-- semantic search (RAG).
--
-- An embedding is a list of 384 numbers that the local all-MiniLM-L6-v2 model produces
-- from a piece of text; texts with similar meaning get nearby lists. Searching by
-- meaning is then "find the chunks whose numbers are closest to the question's numbers",
-- which is what the vector type and the index below make fast.
--
-- The columns are exactly the ones Spring AI's PgVectorStore reads and writes, so it can
-- be pointed at this table with initialize-schema off. metadata holds the drive the chunk
-- came from - driveId, companyName, role - so a search result can say which drive matched,
-- and so re-uploading a drive's PDF can delete that drive's old chunks first.
CREATE TABLE job_description_chunks (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(384)
);

-- HNSW is an approximate nearest-neighbour index: it finds the closest vectors without
-- comparing the question against every row. vector_cosine_ops matches the cosine
-- distance configured for the store in application.yml.
CREATE INDEX job_description_chunks_embedding_idx
    ON job_description_chunks USING hnsw (embedding vector_cosine_ops);
