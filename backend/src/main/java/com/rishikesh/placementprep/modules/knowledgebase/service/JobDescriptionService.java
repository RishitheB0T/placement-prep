package com.rishikesh.placementprep.modules.knowledgebase.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch;

/**
 * Semantic search over job-description PDFs - the retrieval half of RAG
 * (retrieval-augmented generation).
 *
 * <p>Indexing: a PDF is read page by page, split into short passages, and each passage is
 * turned into an embedding - 384 numbers that capture its meaning - by the local model,
 * then stored in the job_description_chunks table. Searching: the question is embedded
 * the same way, and the passages whose numbers sit closest to it are returned. That is
 * why "backend role with databases" can find a passage that says "Spring Boot and SQL"
 * without sharing a single word with it.
 *
 * <p>Takes plain values rather than a DriveDTO, so this module does not depend on the
 * drive module and the drive module can call {@link #forgetDrive} without a cycle.
 */
@Service
public class JobDescriptionService {

    /** How many passages a search returns when the caller does not say. */
    public static final int DEFAULT_RESULTS = 5;

    /** Below this similarity a passage has too little to do with the question to show. */
    private static final double MIN_SIMILARITY = 0.2;

    /**
     * About 200 tokens a passage. The embedding model only reads the first 256 word pieces
     * of any text, so a longer passage would have its end silently ignored, and a shorter
     * one gives a search result precise enough to quote.
     */
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(200)
            .withMinChunkSizeChars(80)
            .withMinChunkLengthToEmbed(20)
            .withMaxNumChunks(200)
            .withKeepSeparator(true)
            .build();

    private final Optional<VectorStore> vectorStore;

    public JobDescriptionService(Optional<VectorStore> vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Makes a drive's job-description PDF searchable, replacing any earlier one.
     *
     * <p>{@code @Transactional} so the replace is all or nothing: if storing the new
     * passages fails, the delete of the old ones is rolled back with it.
     *
     * @return how many passages were stored. Zero means the PDF had no readable text -
     *         typically a scanned image - and nothing was changed.
     */
    @Transactional
    public int index(Long driveId, String companyName, String role, byte[] pdf) {
        VectorStore store = requireVectorStore();
        // 1. Read the PDF, one document per page
        List<Document> pages = new PagePdfDocumentReader(named(pdf)).get();

        // 2. Split the pages into passages small enough to embed well
        List<Document> passages = splitter.apply(pages);
        if (passages.isEmpty()) {
            return 0;
        }

        // 3. Label every passage with the drive it belongs to, so a match can say which
        //    drive it came from and a re-upload can find the old passages to remove. The
        //    reader keeps the page's layout as runs of spaces ("Spring   Boot"), which
        //    would show up in every search excerpt, so whitespace is collapsed here.
        List<Document> labelled = passages.stream()
                .map(passage -> new Document(passage.getText().replaceAll("\\s+", " ").trim(), Map.of(
                        "driveId", driveId,
                        "companyName", companyName,
                        "role", role)))
                .toList();

        // 4. Replace: drop this drive's previous passages, then embed and store the new ones
        forgetDrive(driveId);
        store.add(labelled);
        return labelled.size();
    }

    /** The passages closest in meaning to the query, best first. */
    public List<JobDescriptionMatch> search(String query, int limit) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(limit)
                .similarityThreshold(MIN_SIMILARITY)
                .build();

        return requireVectorStore().similaritySearch(request).stream()
                .map(this::toMatch)
                .toList();
    }

    /** Removes a drive's passages - on re-upload, and when the drive itself is deleted. */
    public void forgetDrive(Long driveId) {
        vectorStore.ifPresent(store -> store.delete("driveId == " + driveId));
    }

    private VectorStore requireVectorStore() {
        return vectorStore.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Semantic search is disabled in this free showcase."));
    }

    private JobDescriptionMatch toMatch(Document passage) {
        Map<String, Object> metadata = passage.getMetadata();
        return new JobDescriptionMatch(
                // Read back from JSON, so the number may come back as any numeric type.
                ((Number) metadata.get("driveId")).longValue(),
                (String) metadata.get("companyName"),
                (String) metadata.get("role"),
                passage.getText(),
                passage.getScore() == null ? 0 : passage.getScore());
    }

    /** The PDF reader records a file name per page; a bare byte array has none to give. */
    private static ByteArrayResource named(byte[] pdf) {
        return new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "job-description.pdf";
            }
        };
    }
}
