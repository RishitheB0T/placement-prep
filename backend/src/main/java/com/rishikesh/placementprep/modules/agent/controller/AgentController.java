package com.rishikesh.placementprep.modules.agent.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.agent.dto.ChatChunk;
import com.rishikesh.placementprep.modules.agent.dto.ChatRequest;
import com.rishikesh.placementprep.modules.agent.dto.ChatResponse;
import com.rishikesh.placementprep.modules.agent.service.AgentService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Talking to the placement assistant. Students and recruiters only - SecurityConfig
 * restricts /api/agent/**, since every tool the assistant has acts as a student.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private static final String PROVIDER_FAILED = "The assistant could not answer just now. Try again in a moment.";

    private final AgentService agentService;
    private final UserService userService;

    public AgentController(AgentService agentService, UserService userService) {
        this.agentService = agentService;
        this.userService = userService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@AuthenticationPrincipal UserDetails principal,
                             @Valid @RequestBody ChatRequest request) {
        UserProfileDTO student = requireAssistantFor(principal);

        try {
            return new ChatResponse(agentService.chat(student, request.message(), historyOf(request)));
        } catch (RuntimeException e) {
            // 502: the model provider failed, not this server or the request. The real
            // cause goes to the log; the student gets something they can act on.
            log.warn("Assistant request failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, PROVIDER_FAILED);
        }
    }

    /**
     * The same conversation as POST /chat, but the reply is sent piece by piece while the
     * model writes it, as Server-Sent Events: a "token" event for each piece of text, then
     * one "done" event carrying the whole reply. A student sees the answer start within a
     * second or two rather than staring at a spinner until the last word is written.
     *
     * <p>Each event's data is JSON, even for a piece of plain text. Spring writes "data:"
     * with no space after it, and the SSE format drops one leading space from the data, so
     * a raw piece such as " world" would lose its space. Inside a JSON string it cannot.
     *
     * <p>Once the first event has gone, the status is already 200 and cannot change. A
     * provider failure part-way through therefore arrives as an "error" event holding the
     * same problem-detail shape every other error in this API has.
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> chatStream(@AuthenticationPrincipal UserDetails principal,
                                               @Valid @RequestBody ChatRequest request) {
        // Checked before anything is streamed, so these still get their real 503 or 404.
        UserProfileDTO student = requireAssistantFor(principal);
        List<ChatRequest.Turn> history = historyOf(request);

        StringBuilder reply = new StringBuilder();
        return Flux.defer(() -> agentService.stream(student, request.message(), history))
                .<ServerSentEvent<?>>map(text -> {
                    reply.append(text);
                    return ServerSentEvent.builder(new ChatChunk(text)).event("token").build();
                })
                .concatWith(Mono.fromSupplier(() ->
                        ServerSentEvent.builder(new ChatResponse(reply.toString())).event("done").build()))
                .onErrorResume(e -> {
                    log.warn("Streamed assistant request failed", e);
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, PROVIDER_FAILED);
                    return Flux.just(ServerSentEvent.builder(problem).event("error").build());
                });
    }

    /** The signed-in student, or the reason the assistant cannot help them. */
    private UserProfileDTO requireAssistantFor(UserDetails principal) {
        // 503: the request is fine, the server just has no model to send it to.
        if (!agentService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI assistant is not switched on for this server yet.");
        }
        return userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account no longer exists"));
    }

    private static List<ChatRequest.Turn> historyOf(ChatRequest request) {
        return request.history() == null ? List.of() : request.history();
    }
}
