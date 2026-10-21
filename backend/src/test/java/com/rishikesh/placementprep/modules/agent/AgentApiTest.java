package com.rishikesh.placementprep.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.JwtService;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;
import com.sun.net.httpserver.HttpServer;

/**
 * The assistant's tool-calling loop, end to end, without a paid API key.
 *
 * <p>Spring AI is pointed at {@link FakeAnthropic}, a tiny HTTP server that answers the
 * way Claude does: first by asking for a tool, then - once it has been sent the tool's
 * result - with a final reply. Everything between is real: Spring AI's Anthropic client,
 * its tool-calling loop, AssistantTools, the services, and PostgreSQL. So when the second
 * request the fake receives contains "Zoho", that proves the model's tool call really ran
 * the Java method and its result really went back to the model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AgentApiTest {

    private static final FakeAnthropic FAKE = FakeAnthropic.start();
    private static final Instant FUTURE = Instant.now().plus(30, ChronoUnit.DAYS);

    @DynamicPropertySource
    static void pointSpringAiAtTheFake(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.anthropic.base-url", FAKE::url);
        registry.add("spring.ai.anthropic.api-key", () -> "test-key");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private DriveRepository driveRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private String studentToken;
    private Long studentId;
    private Long driveId;

    @BeforeEach
    void startFromEmptyTablesAndAFreshFake() {
        applicationRepository.deleteAll();
        driveRepository.deleteAll();
        userRepository.deleteAll();
        FAKE.reset();

        User student = new User();
        student.setEmail("student@cse.nits.ac.in");
        student.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        student.setRole(Role.STUDENT);
        student.setCgpa(new BigDecimal("8.50"));
        student.setBranch("CSE");
        student.setTenthPercentage(new BigDecimal("90"));
        student.setTwelfthPercentage(new BigDecimal("88"));
        student.setBacklogs(0);
        studentId = userRepository.save(student).getId();
        studentToken = jwtService.generateToken("student@cse.nits.ac.in", Role.STUDENT);

        Drive drive = new Drive();
        drive.setCompanyName("Zoho");
        drive.setRole("Member Technical Staff");
        drive.setCtc(new BigDecimal("900000"));
        drive.setTier(1);
        drive.setCgpaCutoff(new BigDecimal("7.5"));
        drive.setBacklogsAllowed(false);
        drive.setEligibleBranches(List.of("CSE"));
        drive.setApplicationDeadline(FUTURE);
        driveId = driveRepository.save(drive).getId();
    }

    @Test
    void theModelCallsAToolAndTheJavaMethodsResultGoesBackToIt() throws Exception {
        FAKE.respondWith(
                FakeAnthropic.toolUse("eligibleDrives", "{}"),
                FakeAnthropic.text("You can apply to Zoho."));

        mockMvc.perform(chat("Which drives can I apply to?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("You can apply to Zoho."));

        assertThat(FAKE.requests()).hasSize(2);
        // The first request told the model which tools exist...
        assertThat(FAKE.requests().get(0)).contains("eligibleDrives", "applyToDrive", "searchJobDescriptions");
        // ...and the second carried eligibleDrives' real result: the drive from PostgreSQL.
        assertThat(FAKE.requests().get(1)).contains("tool_result", "Zoho", "Member Technical Staff");
    }

    @Test
    void aPlainEnglishRequestCanApplyThroughTheSameServiceTheApplyButtonUses() throws Exception {
        FAKE.respondWith(
                FakeAnthropic.toolUse("applyToDrive", "{\"driveId\": " + driveId + "}"),
                FakeAnthropic.text("Done - you have applied to Zoho."));

        mockMvc.perform(chat("Please apply me to the Zoho drive"))
                .andExpect(status().isOk());

        assertThat(applicationRepository.findByStudentIdAndDriveId(studentId, driveId))
                .get().extracting(a -> a.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(FAKE.requests().get(1)).contains("Applied to Zoho");
    }

    @Test
    void earlierTurnsAreSentToTheModelSoFollowUpsMakeSense() throws Exception {
        FAKE.respondWith(FakeAnthropic.text("Sure."));

        mockMvc.perform(post("/api/agent/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "And the deadline?",
                                 "history": [
                                   {"text": "Tell me about Zoho", "fromAssistant": false},
                                   {"text": "Zoho is hiring Member Technical Staff.", "fromAssistant": true}
                                 ]}
                                """))
                .andExpect(status().isOk());

        assertThat(FAKE.requests().getFirst()).contains("Tell me about Zoho", "Zoho is hiring", "And the deadline?");
    }

    @Test
    void aFailureAtTheModelProviderIs502WithAMessageAStudentCanActOn() throws Exception {
        FAKE.failWith(400);

        mockMvc.perform(chat("Hello"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The assistant could not answer just now. Try again in a moment."));
    }

    @Test
    void thePersonInChargeCannotUseTheAssistant() throws Exception {
        User pic = new User();
        pic.setEmail("pic@tnp.nits.ac.in");
        pic.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        pic.setRole(Role.TNP_PIC);
        pic.setBacklogs(0);
        userRepository.save(pic);

        mockMvc.perform(post("/api/agent/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken("pic@tnp.nits.ac.in", Role.TNP_PIC))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Hello\"}"))
                .andExpect(status().isForbidden());

        assertThat(FAKE.requests()).isEmpty();
    }

    private org.springframework.test.web.servlet.RequestBuilder chat(String message) {
        return post("/api/agent/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\": \"" + message + "\"}");
    }

    /**
     * A stand-in for api.anthropic.com, built on the JDK's own HTTP server. Replays the
     * queued responses in order and records every request body it was sent.
     */
    static final class FakeAnthropic {

        private final HttpServer server;
        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private int failWithStatus;

        private FakeAnthropic(HttpServer server) {
            this.server = server;
        }

        static FakeAnthropic start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                FakeAnthropic fake = new FakeAnthropic(server);
                server.createContext("/", exchange -> {
                    fake.requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    int status;
                    String body;
                    synchronized (fake) {
                        if (fake.failWithStatus != 0) {
                            status = fake.failWithStatus;
                            body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fake failure\"}}";
                        } else {
                            status = 200;
                            body = fake.responses.isEmpty() ? text("(no response queued)") : fake.responses.poll();
                        }
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
                server.start();
                return fake;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        synchronized void reset() {
            responses.clear();
            requests.clear();
            failWithStatus = 0;
        }

        synchronized void respondWith(String... bodies) {
            responses.addAll(List.of(bodies));
        }

        synchronized void failWith(int status) {
            failWithStatus = status;
        }

        List<String> requests() {
            return requests;
        }

        /** A Messages API reply in which the model asks for one tool to be run. */
        static String toolUse(String toolName, String inputJson) {
            return message("""
                    [{"type": "tool_use", "id": "toolu_01", "name": "%s", "input": %s}]""".formatted(toolName, inputJson),
                    "tool_use");
        }

        /** A Messages API reply with the model's final text. */
        static String text(String text) {
            return message("""
                    [{"type": "text", "text": "%s"}]""".formatted(text), "end_turn");
        }

        private static String message(String content, String stopReason) {
            return """
                    {"id": "msg_01", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                     "content": %s, "stop_reason": "%s", "stop_sequence": null,
                     "usage": {"input_tokens": 10, "output_tokens": 5}}""".formatted(content, stopReason);
        }
    }
}
