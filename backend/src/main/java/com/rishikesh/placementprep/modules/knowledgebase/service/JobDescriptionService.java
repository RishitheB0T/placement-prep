package com.rishikesh.placementprep.modules.knowledgebase.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch;
import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch.MatchedBy;
import com.rishikesh.placementprep.modules.knowledgebase.repository.JobDescriptionChunkRepository;

/**
 * Semantic search over job-description PDFs - the retrieval half of RAG
 * (retrieval-augmented generation).
 *
 * <p>Indexing: a PDF is read page by page, split into short passages, and each passage is
 * turned into an embedding - 384 numbers that capture its meaning - by the local model,
 * then stored in the job_description_chunks table. Searching: the question is embedded
 * the same way, and the passages whose numbers sit closest to it are returned. That is
 * why "backend role with databases" can find a passage that says "Spring Boot and SQL"
 * without sharing a single word with it. A search also looks for the query's own words
 * (JobDescriptionChunkRepository) and merges the two - see {@link #search}.
 *
 * <p>Takes plain values rather than a DriveDTO, so this module does not depend on the
 * drive module and the drive module can call {@link #forgetDrive} without a cycle.
 *
 * <p>The vector store is optional. A deployment too small for the local embedding model
 * (the free showcase) leaves it out; {@link #isEnabled()} then answers false, the callers
 * decide what that means for them - a 503 over HTTP, a message to the model - and forgetting
 * a drive's passages quietly does nothing, since there are none.
 */
@Service
public class JobDescriptionService {

    /** How many passages a search returns when the caller does not say. */
    public static final int DEFAULT_RESULTS = 5;

    /** Why search and indexing are unavailable where the vector store is left out. */
    public static final String DISABLED_MESSAGE = "Semantic search is switched off on this server";

    /** Below this similarity a passage has too little to do with the question to show. */
    private static final double MIN_SIMILARITY = 0.2;

    /** How many passages each half of a search puts forward before the two are merged. */
    private static final int CANDIDATES = 20;

    /**
     * The k of reciprocal rank fusion, in 1 / (k + rank). 60 is the value the method was
     * published with and the usual default. It flattens the curve: first place in one list
     * is worth only a little more than fifth, so agreement between the lists counts for
     * more than either list's own top pick.
     */
    private static final int RRF_K = 60;

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
    private final JobDescriptionChunkRepository chunkRepository;

    public JobDescriptionService(Optional<VectorStore> vectorStore, JobDescriptionChunkRepository chunkRepository) {
        this.vectorStore = vectorStore;
        this.chunkRepository = chunkRepository;
    }

    /** Whether semantic search is switched on - false where the vector store is left out. */
    public boolean isEnabled() {
        return vectorStore.isPresent();
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

    /**
     * The passages that best match the query, best first - found two ways, then merged.
     *
     * <p>By meaning, the vector search finds passages that say the same thing in other
     * words: "server-side programming" finds "REST APIs in Java". By words, PostgreSQL's
     * full-text search finds the exact terms an embedding can blur - a library, an
     * acronym, a tool's name. Each is good at what the other misses, so this is a hybrid
     * search: both halves put forward their best candidates in order, and reciprocal rank
     * fusion merges the two orders into one.
     */
    public List<JobDescriptionMatch> search(String query, int limit) {
        // 1. By meaning, as before - only passages at least somewhat close to the query
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(CANDIDATES)
                .similarityThreshold(MIN_SIMILARITY)
                .build();
        List<Ranked> byMeaning = requireVectorStore().similaritySearch(request).stream()
                .map(passage -> new Ranked(passage.getId(), toMatch(passage)))
                .toList();

        // 2. By words, over the same stored passages
        List<Ranked> byKeywords = chunkRepository.searchByKeywords(query, CANDIDATES).stream()
                .map(passage -> new Ranked(passage.id(), new JobDescriptionMatch(passage.driveId(),
                        passage.companyName(), passage.role(), passage.text(), 0, MatchedBy.KEYWORDS)))
                .toList();

        // 3. One list from the two
        return fuse(byMeaning, byKeywords, limit);
    }

    /**
     * Reciprocal rank fusion: each passage earns 1 / (RRF_K + its rank) from every list it
     * is in, and the passages are ordered by what they earned in total. A passage both
     * searches found therefore rises above one that only a single search ranked first.
     *
     * <p>Only the ranks are used, never the scores - which is the point. A cosine similarity
     * and a full-text rank are measured on scales that cannot be compared, so adding them
     * up would mean nothing; positions in a list can always be compared.
     *
     * <p>A passage in both lists is labelled BOTH and keeps its similarity from the search
     * by meaning. On an exact tie the passage first seen wins, and the meaning list is read
     * first.
     */
    private static List<JobDescriptionMatch> fuse(List<Ranked> byMeaning, List<Ranked> byKeywords, int limit) {
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, JobDescriptionMatch> matches = new HashMap<>();

        for (List<Ranked> ranking : List.of(byMeaning, byKeywords)) {
            for (int index = 0; index < ranking.size(); index++) {
                Ranked found = ranking.get(index);
                int rank = index + 1;
                fusedScores.merge(found.passageId(), 1.0 / (RRF_K + rank), Double::sum);
                matches.merge(found.passageId(), found.match(),
                        (alreadyFound, foundAgain) -> alreadyFound.withMatchedBy(MatchedBy.BOTH));
            }
        }

        // A stable sort, so equal totals stay in the order they were first seen.
        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> matches.get(entry.getKey()))
                .toList();
    }

    /** A passage as one half of a search found it, before the two halves are merged. */
    private record Ranked(String passageId, JobDescriptionMatch match) {}

    /**
     * The passages of one drive's job description closest in meaning to the query, best
     * first - the retrieval half of RAG for the resume reviewer, which puts them in front
     * of the model so feedback can quote what this job actually asks for.
     *
     * <p>The filter keeps the search inside this drive's passages, and there is no
     * similarity threshold: any passage of the right job description is relevant context,
     * the query only decides the order. Where semantic search is switched off (the free
     * showcase), there are simply no passages and the review runs without them.
     *
     * @return the passage texts; empty when no job description was uploaded for the drive
     */
    public List<String> passagesFor(Long driveId, String query, int limit) {
        if (vectorStore.isEmpty()) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(limit)
                .filterExpression("driveId == " + driveId)
                .build();

        return vectorStore.get().similaritySearch(request).stream()
                .map(Document::getText)
                .toList();
    }

    /** Removes a drive's passages - on re-upload, and when the drive itself is deleted. */
    public void forgetDrive(Long driveId) {
        vectorStore.ifPresent(store -> store.delete("driveId == " + driveId));
    }

    /**
     * The vector store, for the operations that cannot work without it. Callers check
     * {@link #isEnabled()} first; this is the backstop, and a plain domain exception rather
     * than an HTTP one, so the service stays free of web types.
     */
    private VectorStore requireVectorStore() {
        return vectorStore.orElseThrow(() -> new IllegalStateException(DISABLED_MESSAGE));
    }

    private JobDescriptionMatch toMatch(Document passage) {
        Map<String, Object> metadata = passage.getMetadata();
        return new JobDescriptionMatch(
                // Read back from JSON, so the number may come back as any numeric type.
                ((Number) metadata.get("driveId")).longValue(),
                (String) metadata.get("companyName"),
                (String) metadata.get("role"),
                passage.getText(),
                passage.getScore() == null ? 0 : passage.getScore(),
                MatchedBy.MEANING);
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
