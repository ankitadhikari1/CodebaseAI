
package devPilot.backend.services.ai;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);

        StringBuffer fullReply = new StringBuffer();
        AtomicBoolean terminated = new AtomicBoolean(false);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        emitter.onCompletion(() -> terminated.set(true));

        emitter.onTimeout(() -> {
            terminated.set(true);
            log.warn("Chat SSE timed out for session {}", sessionId);
        });

        emitter.onError(error -> {
            terminated.set(true);

            if (isClientDisconnect(error)) {
                clientDisconnected.set(true);
                log.debug("Client disconnected from chat stream: {}", sessionId);
            } else {
                log.warn("SSE connection error for session {}", sessionId, error);
            }
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));

            Disposable subscription = ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        if (terminated.get() || clientDisconnected.get()) {
                            return;
                        }

                        fullReply.append(token);

                        try {
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(token, MediaType.TEXT_PLAIN));
                        } catch (IOException | IllegalStateException ex) {
                            clientDisconnected.set(true);
                            throw new IllegalStateException(
                                    "Unable to send token to SSE client", ex);
                        }
                    })
                    .doOnError(error -> {
                        if (isClientDisconnect(error)
                                || clientDisconnected.get()) {
                            log.debug(
                                    "Chat stream ended after client disconnect: {}",
                                    sessionId);
                            return;
                        }

                        log.error("Chat generation failed for session {}",
                                sessionId, error);

                        sendErrorEvent(emitter, error, terminated);
                    })
                    .doOnComplete(() -> {
                        if (terminated.get() || clientDisconnected.get()) {
                            return;
                        }

                        completeStream(
                                emitter,
                                sessionId,
                                fullReply.toString(),
                                citations,
                                terminated);
                    })
                    .subscribe();

            emitter.onTimeout(subscription::dispose);
            emitter.onCompletion(subscription::dispose);

        } catch (Exception ex) {
            log.error("Unable to start chat stream for session {}",
                    sessionId, ex);

            sendErrorEvent(emitter, ex, terminated);
        }

        return emitter;
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            String reply,
            List<CitationDto> citations,
            AtomicBoolean terminated) {

        if (!terminated.compareAndSet(false, true)) {
            return;
        }

        try {
            ChatMessage assistant = chatMessageRepository.save(
                    ChatMessage.builder()
                            .sessionId(sessionId)
                            .role(MessageRole.ASSISTANT)
                            .content(reply)
                            .citations(citationMapper.toJson(citations))
                            .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));

            emitter.send(SseEmitter.event()
                    .name("done")
                    .data("[DONE]"));

            emitter.complete();

        } catch (Exception ex) {
            log.error("Failed to save or complete assistant response for session {}",
                    sessionId, ex);

            emitter.completeWithError(ex);
        }
    }

    private void sendErrorEvent(
            SseEmitter emitter,
            Throwable error,
            AtomicBoolean terminated) {

        if (!terminated.compareAndSet(false, true)) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("The AI response could not be generated. Please try again.",
                            MediaType.TEXT_PLAIN));

            emitter.complete();

        } catch (Exception sendError) {
            log.debug("Could not send SSE error event; connection may be closed",
                    sendError);
            emitter.completeWithError(error);
        }
    }

    private boolean isClientDisconnect(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }

            String message = current.getMessage();

            if (message != null) {
                String lower = message.toLowerCase();

                if (lower.contains("connection reset")
                        || lower.contains("broken pipe")
                        || lower.contains("connection aborted")
                        || lower.contains("client abort")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}