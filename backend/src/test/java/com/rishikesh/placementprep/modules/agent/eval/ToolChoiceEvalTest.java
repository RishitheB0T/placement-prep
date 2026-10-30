package com.rishikesh.placementprep.modules.agent.eval;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import com.rishikesh.placementprep.modules.agent.dto.ChatResponse;
import com.rishikesh.placementprep.modules.agent.eval.ToolChoiceEval.Case;
import com.rishikesh.placementprep.modules.agent.eval.ToolChoiceEval.Dataset;
import com.rishikesh.placementprep.modules.agent.eval.ToolChoiceEval.Outcome;
import com.rishikesh.placementprep.modules.agent.eval.ToolChoiceEval.Report;
import com.rishikesh.placementprep.modules.agent.service.AssistantTools;

/**
 * The evaluation harness, checked without a model: the scoring rules, the report, and the
 * dataset itself. These run in every build, so a broken evaluation is caught before anyone
 * pays to run it.
 */
class ToolChoiceEvalTest {

    private static final Case ASK_ONLY = new Case("ask-only", "Am I eligible for Zoho? Don't apply yet.",
            List.of("eligibleDrives"), List.of("applyToDrive"));

    @Test
    void aCasePassesWhenEveryExpectedToolIsCalledAndNoForbiddenOne() {
        // An extra tool that is neither expected nor forbidden is allowed: looking at all
        // drives before answering is a reasonable way to get there.
        Outcome outcome = ToolChoiceEval.score(ASK_ONLY, List.of("allDrives", "eligibleDrives"), "Yes, you are.");

        assertThat(outcome.passed()).isTrue();
    }

    @Test
    void aMissingExpectedToolFailsTheCase() {
        Outcome outcome = ToolChoiceEval.score(ASK_ONLY, List.of("allDrives"), "Probably.");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.missing()).containsExactly("eligibleDrives");
    }

    @Test
    void aForbiddenToolFailsTheCaseEvenWhenTheExpectedOnesWereCalled() {
        Outcome outcome = ToolChoiceEval.score(ASK_ONLY, List.of("eligibleDrives", "applyToDrive"), "Applied!");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.forbiddenUsed()).containsExactly("applyToDrive");
    }

    @Test
    void aCaseThatErrorsCountsAsFailedAndTheRestStillRun() {
        Dataset dataset = new Dataset(0.5, List.of(
                new Case("breaks", "boom", List.of(), List.of()),
                new Case("fine", "hello", List.of(), List.of("applyToDrive"))));

        Report report = ToolChoiceEval.run(dataset, message -> {
            if (message.equals("boom")) {
                throw new IllegalStateException("provider down");
            }
            return new ChatResponse("Hello!", List.of());
        });

        assertThat(report.outcomes()).extracting(Outcome::passed).containsExactly(false, true);
        assertThat(report.passRate()).isEqualTo(0.5);
        assertThat(report.meetsRequiredPassRate()).isTrue();
        assertThat(report.toMarkdown("Eval"))
                .contains("1 of 2 cases passed (50%)")
                .contains("FAIL - error: IllegalStateException: provider down");
    }

    @Test
    void theReportSaysWhyEachFailedCaseFailed() {
        Report report = new Report(List.of(ToolChoiceEval.score(ASK_ONLY, List.of("applyToDrive"), "Done.")), 0.8);

        assertThat(report.meetsRequiredPassRate()).isFalse();
        assertThat(report.toMarkdown("Eval"))
                .contains("0 of 1 cases passed (0%); at least 80% must pass.")
                .contains("| ask-only | Am I eligible for Zoho? Don't apply yet. | eligibleDrives | applyToDrive | "
                        + "FAIL - did not call eligibleDrives; called applyToDrive |");
    }

    /**
     * A tool name the assistant does not have would make a case impossible to pass - or a
     * forbid list impossible to break - without anyone noticing, so the dataset is held to
     * the real @Tool methods. Every tool is expected somewhere, so each one is measured.
     */
    @Test
    void theDatasetOnlyNamesRealToolsAndCoversEveryOne() throws IOException {
        Dataset dataset = ToolChoiceEval.load();
        Set<String> realTools = Arrays.stream(AssistantTools.class.getMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .collect(toSet());

        assertThat(dataset.passRate()).isBetween(0.0, 1.0);
        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(10)
                .extracting(Case::id).doesNotHaveDuplicates();
        assertThat(dataset.cases()).allSatisfy(evalCase -> {
            assertThat(evalCase.message()).isNotBlank();
            assertThat(evalCase.expect()).allMatch(realTools::contains);
            assertThat(evalCase.forbid()).allMatch(realTools::contains);
            assertThat(evalCase.expect()).noneMatch(evalCase.forbid()::contains);
        });
        assertThat(dataset.cases().stream().flatMap(evalCase -> evalCase.expect().stream()).toList())
                .containsAll(realTools);
    }
}
