package com.rishikesh.placementprep.modules.agent.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.rishikesh.placementprep.modules.agent.dto.ChatResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * An evaluation of the assistant's tool choice: for each message in a fixed set, did the
 * model call the tools it should have, and stay away from the ones it must not?
 *
 * <p>Unit tests check code that always behaves the same. A model does not, so instead of
 * one assertion per answer, an evaluation runs a whole set of cases and measures how many
 * pass - and the result is compared with a pass rate, not with "all of them". Running the
 * same set after changing the system prompt or a tool description, or switching models,
 * shows whether the change helped or hurt.
 *
 * <p>Tool choice is scored rather than the wording of the reply, because it can be checked
 * exactly: either applyToDrive was called or it was not. That matters most for the one
 * tool that changes something - a reply that applies to a drive the student only asked
 * about is a real mistake, whatever it says.
 *
 * <p>Plain Java with no Spring and no model, so ToolChoiceEvalTest can check the scoring
 * itself in every build; AssistantEvalTest is what runs it against the real model.
 */
public final class ToolChoiceEval {

    /** Where the cases live, on the test classpath. */
    public static final String DATASET = "/eval/assistant-tool-choice.json";

    private ToolChoiceEval() {
    }

    /** The cases, and the share of them that must pass. */
    public record Dataset(double passRate, List<Case> cases) {}

    /**
     * One message to send, with no earlier conversation.
     *
     * @param expect every one of these tools must be called
     * @param forbid none of these may be called
     */
    public record Case(String id, String message, List<String> expect, List<String> forbid) {}

    /** What happened for one case. */
    public record Outcome(Case evalCase, List<String> toolsUsed, List<String> missing, List<String> forbiddenUsed,
                          String reply, String error) {

        public boolean passed() {
            return error == null && missing.isEmpty() && forbiddenUsed.isEmpty();
        }
    }

    /** Every case's outcome, and the pass rate the dataset asks for. */
    public record Report(List<Outcome> outcomes, double requiredPassRate) {

        public long passedCount() {
            return outcomes.stream().filter(Outcome::passed).count();
        }

        public double passRate() {
            return outcomes.isEmpty() ? 0 : (double) passedCount() / outcomes.size();
        }

        public boolean meetsRequiredPassRate() {
            return passRate() >= requiredPassRate;
        }

        /** The report as a Markdown table, one row per case, failures explained. */
        public String toMarkdown(String title) {
            StringBuilder markdown = new StringBuilder()
                    .append("# ").append(title).append("\n\n")
                    .append("%d of %d cases passed (%.0f%%); at least %.0f%% must pass.\n\n".formatted(
                            passedCount(), outcomes.size(), passRate() * 100, requiredPassRate * 100))
                    .append("| Case | Message | Expected | Used | Result |\n")
                    .append("| --- | --- | --- | --- | --- |\n");
            for (Outcome outcome : outcomes) {
                markdown.append("| %s | %s | %s | %s | %s |\n".formatted(
                        outcome.evalCase().id(),
                        cell(outcome.evalCase().message()),
                        namesOrDash(outcome.evalCase().expect()),
                        namesOrDash(outcome.toolsUsed()),
                        verdict(outcome)));
            }
            return markdown.toString();
        }

        private static String verdict(Outcome outcome) {
            if (outcome.error() != null) {
                return "FAIL - error: " + cell(outcome.error());
            }
            if (outcome.passed()) {
                return "pass";
            }
            List<String> reasons = new ArrayList<>();
            if (!outcome.missing().isEmpty()) {
                reasons.add("did not call " + String.join(", ", outcome.missing()));
            }
            if (!outcome.forbiddenUsed().isEmpty()) {
                reasons.add("called " + String.join(", ", outcome.forbiddenUsed()));
            }
            return "FAIL - " + String.join("; ", reasons);
        }

        private static String namesOrDash(List<String> names) {
            return names.isEmpty() ? "-" : String.join(", ", names);
        }

        /** A pipe would end a Markdown table cell early. */
        private static String cell(String text) {
            return text.replace("|", "\\|").replace("\n", " ");
        }
    }

    /** Reads the dataset from the test classpath. */
    public static Dataset load() throws IOException {
        try (InputStream json = ToolChoiceEval.class.getResourceAsStream(DATASET)) {
            if (json == null) {
                throw new IOException("Missing " + DATASET);
            }
            return new ObjectMapper().readValue(json, Dataset.class);
        }
    }

    /**
     * Sends every case through {@code ask} and scores the replies. A case whose call
     * fails - the provider is down, a tool threw - is recorded as failed and the rest
     * still run, so one bad case cannot hide the results of the others.
     */
    public static Report run(Dataset dataset, Function<String, ChatResponse> ask) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Case evalCase : dataset.cases()) {
            try {
                ChatResponse response = ask.apply(evalCase.message());
                outcomes.add(score(evalCase, response.toolsUsed(), response.reply()));
            } catch (RuntimeException e) {
                outcomes.add(new Outcome(evalCase, List.of(), List.of(), List.of(), null,
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return new Report(outcomes, dataset.passRate());
    }

    /** Scores one reply: which expected tools were not called, which forbidden ones were. */
    public static Outcome score(Case evalCase, List<String> toolsUsed, String reply) {
        List<String> missing = evalCase.expect().stream().filter(tool -> !toolsUsed.contains(tool)).toList();
        List<String> forbiddenUsed = evalCase.forbid().stream().filter(toolsUsed::contains).toList();
        return new Outcome(evalCase, toolsUsed, missing, forbiddenUsed, reply, null);
    }
}
