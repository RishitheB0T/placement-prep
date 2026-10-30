package com.rishikesh.placementprep.modules.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch;
import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch.MatchedBy;
import com.rishikesh.placementprep.modules.knowledgebase.repository.JobDescriptionChunkRepository;
import com.rishikesh.placementprep.modules.knowledgebase.repository.JobDescriptionChunkRepository.StoredPassage;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

/**
 * Unit tests for indexing and searching job descriptions, with the vector store mocked.
 *
 * <p>The PDFs here are real ones, built with PDFBox - the same library the PDF reader uses
 * underneath - so the read-and-split half of indexing runs for real.
 */
@ExtendWith(MockitoExtension.class)
class JobDescriptionServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private JobDescriptionChunkRepository chunkRepository;

    @Test
    void indexingLabelsEveryPassageWithItsDriveAndReplacesTheOldOnes() throws IOException {
        JobDescriptionService service = new JobDescriptionService(Optional.of(vectorStore), chunkRepository);

        int passages = service.index(12L, "Zoho", "SDE", pdfWithText(
                "Backend Engineer. Build REST APIs in Java with Spring Boot and PostgreSQL."));

        assertThat(passages).isPositive();

        // The old passages go first, then the new ones - in that order.
        InOrder order = inOrder(vectorStore);
        order.verify(vectorStore).delete("driveId == 12");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> stored = ArgumentCaptor.forClass(List.class);
        order.verify(vectorStore).add(stored.capture());

        assertThat(stored.getValue()).allSatisfy(passage -> {
            assertThat(passage.getMetadata()).containsEntry("driveId", 12L).containsEntry("companyName", "Zoho");
        });
        assertThat(stored.getValue().getFirst().getText()).contains("Spring Boot");
    }

    @Test
    void aPdfWithNoTextIndexesNothingAndLeavesTheOldPassagesAlone() throws IOException {
        JobDescriptionService service = new JobDescriptionService(Optional.of(vectorStore), chunkRepository);

        assertThat(service.index(12L, "Zoho", "SDE", pdfWithText(""))).isZero();

        verify(vectorStore, never()).delete(anyString());
        verify(vectorStore, never()).add(any());
    }

    @Test
    void searchTurnsStoredPassagesIntoMatchesWithTheirDrive() {
        // Metadata read back from JSON may hold the id as an Integer rather than a Long.
        Document passage = Document.builder()
                .text("Strong SQL skills are required.")
                .metadata(Map.of("driveId", 12, "companyName", "Zoho", "role", "SDE"))
                .score(0.71)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(passage));

        List<JobDescriptionMatch> matches = new JobDescriptionService(Optional.of(vectorStore), chunkRepository).search("databases", 5);

        assertThat(matches).containsExactly(
                new JobDescriptionMatch(12L, "Zoho", "SDE", "Strong SQL skills are required.", 0.71, MatchedBy.MEANING));
    }

    /**
     * Reciprocal rank fusion. By meaning the order is A, B; by words it is C, B. B is only
     * second in each list, yet it comes first overall - 1/62 + 1/62 beats the 1/61 that A
     * and C each earn from a single list - because both searches agree on it. A and C tie,
     * and the tie goes to the meaning list.
     */
    @Test
    void aPassageBothSearchesFoundRanksAboveEachSearchesOwnTopPick() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                passage("a", "Java microservices on AWS.", 0.80),
                passage("b", "Strong SQL skills are required.", 0.60)));
        when(chunkRepository.searchByKeywords(eq("SQL"), anyInt())).thenReturn(List.of(
                new StoredPassage("c", 7L, "Infosys", "Analyst", "SQL and Excel reporting."),
                new StoredPassage("b", 12L, "Zoho", "SDE", "Strong SQL skills are required.")));
        JobDescriptionService service = new JobDescriptionService(Optional.of(vectorStore), chunkRepository);

        List<JobDescriptionMatch> matches = service.search("SQL", 5);

        assertThat(matches).extracting(JobDescriptionMatch::excerpt).containsExactly(
                "Strong SQL skills are required.", "Java microservices on AWS.", "SQL and Excel reporting.");
        assertThat(matches).extracting(JobDescriptionMatch::matchedBy).containsExactly(
                MatchedBy.BOTH, MatchedBy.MEANING, MatchedBy.KEYWORDS);
        // B keeps its similarity from the search by meaning; C, found by words alone, has none.
        assertThat(matches).extracting(JobDescriptionMatch::score).containsExactly(0.60, 0.80, 0.0);

        assertThat(service.search("SQL", 2)).hasSize(2);
    }

    private static Document passage(String id, String text, double score) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of("driveId", 12, "companyName", "Zoho", "role", "SDE"))
                .score(score)
                .build();
    }

    /**
     * Without a vector store (the free showcase) search and indexing are refused with a
     * domain exception - the controller turns that into a 503 - while deleting a drive and
     * reviewing a resume still work: there are simply no passages.
     */
    @Test
    void withoutAVectorStoreSearchIsRefusedButNothingElseBreaks() {
        JobDescriptionService service = new JobDescriptionService(Optional.empty(), chunkRepository);

        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.search("databases", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(JobDescriptionService.DISABLED_MESSAGE);
        assertThatThrownBy(() -> service.index(12L, "Zoho", "SDE", new byte[0]))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> service.forgetDrive(12L)).doesNotThrowAnyException();
        assertThat(service.passagesFor(12L, "required skills", 8)).isEmpty();
    }

    /** A one-page PDF with real, selectable text - or none at all, for an empty string. */
    private static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (!text.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
