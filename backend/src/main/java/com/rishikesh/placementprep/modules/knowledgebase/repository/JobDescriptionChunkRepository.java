package com.rishikesh.placementprep.modules.knowledgebase.repository;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keyword search over the stored job-description passages.
 *
 * <p>Spring AI's vector store writes the job_description_chunks table and searches it by
 * meaning. This class reads the same rows by their words instead, with PostgreSQL's
 * full-text search. Plain SQL through JdbcClient rather than a Spring Data interface,
 * because the table has no JPA entity - its rows belong to the vector store.
 */
@Repository
public class JobDescriptionChunkRepository {

    private final JdbcClient jdbcClient;

    public JobDescriptionChunkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Passages containing any of the query's words, those holding more of them - and
     * closer together - first.
     *
     * <p>plainto_tsquery turns the query into stems joined by AND ('java' &amp; 'sql'), which
     * would only find passages holding every word. Swapping AND for OR makes any one word
     * enough to be a candidate; the ranking still puts passages with more of the words
     * first. The result is cast straight to tsquery, not passed through to_tsquery, so the
     * already-stemmed words are not stemmed a second time.
     *
     * <p>A query of nothing but common words ("the and of") has no stems left, and matches
     * nothing rather than failing.
     */
    public List<StoredPassage> searchByKeywords(String query, int limit) {
        return jdbcClient.sql("""
                        WITH search AS (
                            SELECT replace(plainto_tsquery('english', :query)::text, ' & ', ' | ')::tsquery AS words
                        )
                        SELECT c.id::text AS id,
                               c.content,
                               (c.metadata->>'driveId')::bigint AS drive_id,
                               c.metadata->>'companyName' AS company_name,
                               c.metadata->>'role' AS role
                        FROM job_description_chunks c, search
                        WHERE to_tsvector('english', c.content) @@ search.words
                        ORDER BY ts_rank_cd(to_tsvector('english', c.content), search.words) DESC, c.id
                        LIMIT :limit
                        """)
                .param("query", query)
                .param("limit", limit)
                .query((row, rowNumber) -> new StoredPassage(
                        row.getString("id"),
                        row.getLong("drive_id"),
                        row.getString("company_name"),
                        row.getString("role"),
                        row.getString("content")))
                .list();
    }

    /** One stored passage and the drive it belongs to. */
    public record StoredPassage(String id, Long driveId, String companyName, String role, String text) {}
}
