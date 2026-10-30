package com.rishikesh.placementprep.modules.agent.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rishikesh.placementprep.modules.agent.dto.ChatRequest;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

import reactor.core.publisher.Flux;

/**
 * The placement assistant: answers a student's plain-English messages, calling backend
 * service methods through {@link AssistantTools} whenever it needs real data.
 *
 * <p>One model call may involve several round trips - the model asks for a tool, Spring
 * AI runs it and sends back the result, the model asks for another or writes its answer.
 * ChatClient runs that loop; this class only sets up what the model is told and which
 * tools it may use.
 */
@Service
public class AgentService {

    private static final String SYSTEM_PROMPT = """
            You are the placement assistant for a college's training and placement portal,
            talking to one student. Use the tools to look things up - never guess or invent
            a drive, deadline, cutoff or application status. Refer to drives by company and
            role, and mention a drive's id when the student might want to apply to it.
            Only apply to a drive when the student has clearly asked you to apply to that
            specific one. When a question is about skills, responsibilities or the
            selection process, search the job descriptions. Keep answers short and friendly.
            """;

    private final ChatClient chatClient;
    private final DriveService driveService;
    private final ApplicationService applicationService;
    private final JobDescriptionService jobDescriptionService;
    private final String apiKey;

    public AgentService(ChatClient chatClient,
                        DriveService driveService,
                        ApplicationService applicationService,
                        JobDescriptionService jobDescriptionService,
                        @Value("${spring.ai.anthropic.api-key:}") String apiKey) {
        this.chatClient = chatClient;
        this.driveService = driveService;
        this.applicationService = applicationService;
        this.jobDescriptionService = jobDescriptionService;
        this.apiKey = apiKey;
    }

    /** Whether there is an API key to call the model with at all. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chat(UserProfileDTO student, String message, List<ChatRequest.Turn> history) {
        return prompt(student, message, history).call().content();
    }

    /**
     * The same conversation as {@link #chat}, but the reply comes back in pieces as the
     * model writes it, instead of all at once at the end. Tool calls still happen part-way
     * through: Spring AI pauses the stream, runs the tool, sends the result back to the
     * model and carries on streaming its answer.
     *
     * <p>Nothing is sent to the model until the returned Flux is subscribed to.
     */
    public Flux<String> stream(UserProfileDTO student, String message, List<ChatRequest.Turn> history) {
        return prompt(student, message, history).stream().content()
                // Some stream events (the start of a message, a tool call) carry no text.
                .filter(text -> !text.isEmpty());
    }

    /** The request both {@link #chat} and {@link #stream} send, before it is sent. */
    private ChatClient.ChatClientRequestSpec prompt(UserProfileDTO student, String message,
                                                    List<ChatRequest.Turn> history) {
        // 1. The conversation so far, then the new message. Built as message objects rather
        //    than passed through .user(text): plain text there can be read as a template,
        //    and a student typing "{" would then break the request.
        List<Message> messages = new ArrayList<>();
        for (ChatRequest.Turn turn : history) {
            messages.add(turn.fromAssistant() ? new AssistantMessage(turn.text()) : new UserMessage(turn.text()));
        }
        messages.add(new UserMessage(message));

        // 2. Tools bound to this one student
        AssistantTools tools = new AssistantTools(student, driveService, applicationService, jobDescriptionService);

        // 3. What to ask. Today's date lets the model reason about deadlines.
        return chatClient.prompt()
                .system(SYSTEM_PROMPT + "\nToday's date is " + LocalDate.now() + ".")
                .messages(messages)
                .tools(tools);
    }
}
