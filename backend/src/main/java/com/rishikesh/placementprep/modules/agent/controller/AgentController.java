package com.rishikesh.placementprep.modules.agent.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.agent.dto.ChatRequest;
import com.rishikesh.placementprep.modules.agent.dto.ChatResponse;
import com.rishikesh.placementprep.modules.agent.service.AgentService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;

import jakarta.validation.Valid;

/**
 * Talking to the placement assistant. Students and recruiters only - SecurityConfig
 * restricts /api/agent/**, since every tool the assistant has acts as a student.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final UserService userService;

    public AgentController(AgentService agentService, UserService userService) {
        this.agentService = agentService;
        this.userService = userService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@AuthenticationPrincipal UserDetails principal,
                             @Valid @RequestBody ChatRequest request) {
        // 503: the request is fine, the server just has no model to send it to.
        if (!agentService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI assistant is not switched on for this server yet.");
        }

        UserProfileDTO student = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account no longer exists"));

        List<ChatRequest.Turn> history = request.history() == null ? List.of() : request.history();

        try {
            return new ChatResponse(agentService.chat(student, request.message(), history));
        } catch (RuntimeException e) {
            // 502: the model provider failed, not this server or the request. The real
            // cause goes to the log; the student gets something they can act on.
            log.warn("Assistant request failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The assistant could not answer just now. Try again in a moment.");
        }
    }
}
