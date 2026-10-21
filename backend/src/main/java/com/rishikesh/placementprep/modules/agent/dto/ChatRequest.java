package com.rishikesh.placementprep.modules.agent.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/agent/chat: the new message, plus the conversation so far.
 *
 * <p>The server keeps no chat history - every request carries the earlier turns itself,
 * the same stateless choice the rest of the API makes. The caps keep one request from
 * becoming an arbitrarily large (and arbitrarily expensive) model call.
 */
public record ChatRequest(

    @NotBlank @Size(max = 2000)
    String message,

    @Size(max = 20)
    List<@Valid Turn> history
) {

    /** One earlier message, from the student or from the assistant. */
    public record Turn(

        @NotBlank @Size(max = 4000)
        String text,

        boolean fromAssistant
    ) {}
}
