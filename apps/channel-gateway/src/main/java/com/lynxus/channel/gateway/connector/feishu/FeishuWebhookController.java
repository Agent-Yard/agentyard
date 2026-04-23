package com.lynxus.channel.gateway.connector.feishu;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/connectors/feishu")
public class FeishuWebhookController {
    private final FeishuWebhookService feishuWebhookService;

    public FeishuWebhookController(FeishuWebhookService feishuWebhookService) {
        this.feishuWebhookService = feishuWebhookService;
    }

    @PostMapping("/webhook")
    public Object webhook(
        @RequestBody(required = false) Map<String, Object> payload,
        @RequestHeader Map<String, String> headers
    ) {
        return feishuWebhookService.handleWebhook(payload, normalizeHeaders(headers));
    }

    private Map<String, String> normalizeHeaders(Map<String, String> headers) {
        Map<String, String> normalized = new LinkedHashMap<>();
        headers.forEach((key, value) -> normalized.put(key, value));
        return Map.copyOf(normalized);
    }
}
