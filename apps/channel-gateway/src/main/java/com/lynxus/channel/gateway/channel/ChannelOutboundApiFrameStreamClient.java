package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.http.HttpUrls;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

class ChannelOutboundApiFrameStreamClient {
    static final String CHANNEL_OUTBOUND_FRAME_EVENT = "channel-outbound-frame";
    static final String FINAL_REPLAY_WINDOW_EXHAUSTED_EVENT = "final-replay-window-exhausted";

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    ChannelOutboundApiFrameStreamClient(
        ObjectMapper objectMapper,
        String apiBaseUrl,
        String internalAuthToken,
        HttpClient httpClient
    ) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = requireText(apiBaseUrl, "lynxus.api.base-url");
        this.internalAuthToken = requireText(internalAuthToken, "lynxus.internal-auth.token");
        this.httpClient = httpClient;
    }

    void stream(StreamRequest request, StreamHandler handler) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(streamUri(request.channelProfileId()))
            .GET()
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + internalAuthToken)
            .header("X-Lynxus-Max-Final-Replay-Frames", Integer.toString(request.maxFinalReplayFrames()));
        if (hasText(request.lastEventId())) {
            builder.header("Last-Event-ID", request.lastEventId());
        }
        if (request.lastAckedFinalSequence() != null) {
            builder.header("X-Lynxus-Last-Acked-Final-Sequence", Long.toString(request.lastAckedFinalSequence()));
        }
        if (hasText(request.lastAckedSessionId())) {
            builder.header("X-Lynxus-Last-Acked-Session-Id", request.lastAckedSessionId());
        }
        if (hasText(request.lastAckedSessionMessageId())) {
            builder.header("X-Lynxus-Last-Acked-Session-Message-Id", request.lastAckedSessionMessageId());
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IllegalStateException("channel outbound frame stream failed with HTTP " + response.statusCode());
        }
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            handler.onOpen(body);
            readEvents(reader, handler);
        } finally {
            handler.onClosed();
        }
    }

    private URI streamUri(String channelProfileId) {
        String query = "channelProfileId=" + URLEncoder.encode(requireText(channelProfileId, "channelProfileId"), StandardCharsets.UTF_8);
        return URI.create(HttpUrls.join(apiBaseUrl, "/internal/channel-outbound/frames/stream") + "?" + query);
    }

    private void readEvents(BufferedReader reader, StreamHandler handler) throws Exception {
        String id = null;
        String event = null;
        List<String> data = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                dispatch(id, event, data, handler);
                id = null;
                event = null;
                data.clear();
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("id:")) {
                id = line.substring(3).trim();
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                data.add(line.substring(5).trim());
            }
        }
        dispatch(id, event, data, handler);
    }

    private void dispatch(String id, String event, List<String> data, StreamHandler handler) throws Exception {
        if (event == null || event.isBlank()) {
            return;
        }
        if (CHANNEL_OUTBOUND_FRAME_EVENT.equals(event)) {
            ChannelOutboundFrame frame = objectMapper.readValue(String.join("\n", data), ChannelOutboundFrame.class);
            handler.onFrame(id, frame);
            return;
        }
        if (FINAL_REPLAY_WINDOW_EXHAUSTED_EVENT.equals(event)) {
            handler.onFinalReplayWindowExhausted();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    record StreamRequest(
        String channelProfileId,
        String lastEventId,
        Long lastAckedFinalSequence,
        String lastAckedSessionId,
        String lastAckedSessionMessageId,
        int maxFinalReplayFrames
    ) {
    }

    interface StreamHandler {
        default void onOpen(InputStream body) {
        }

        default void onClosed() {
        }

        void onFrame(String streamCursor, ChannelOutboundFrame frame) throws Exception;

        void onFinalReplayWindowExhausted();
    }
}
