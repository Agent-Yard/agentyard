package com.lynxus.channel.gateway.connector.feishu;

import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.DeleteMessageReq;
import com.lark.oapi.service.im.v1.model.DeleteMessageResp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class FeishuSdkMessageSender implements FeishuMessageSender {
    private final ObjectMapper objectMapper;

    FeishuSdkMessageSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public FeishuCreateCardResult createCard(FeishuCreateCardCommand command) {
        try {
            CreateCardReq request = CreateCardReq.newBuilder()
                .createCardReqBody(CreateCardReqBody.newBuilder()
                    .type("card_json")
                    .data(command.cardJson())
                    .build())
                .build();
            CreateCardResp response = client(command.credential()).cardkit().v1().card().create(request);
            if (!response.success()) {
                throw new IllegalStateException(feishuError("feishu create card failed", response.getCode(), response.getMsg(), response.getRequestId()));
            }
            String cardId = response.getData() == null ? null : response.getData().getCardId();
            if (cardId == null || cardId.isBlank()) {
                throw new IllegalStateException("feishu create card returned blank cardId");
            }
            return new FeishuCreateCardResult(cardId, metadata(response.getRequestId()));
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to create feishu card", error);
        }
    }

    @Override
    public FeishuSendInteractiveCardResult sendInteractiveCard(FeishuSendInteractiveCardCommand command) {
        try {
            CreateMessageReq request = CreateMessageReq.newBuilder()
                .receiveIdType(command.receiveIdType())
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                    .receiveId(command.receiveId())
                    .msgType("interactive")
                    .content(objectMapper.writeValueAsString(Map.of(
                        "type", "card",
                        "data", Map.of("card_id", command.cardId())
                    )))
                    .uuid(command.uuid())
                    .build())
                .build();
            CreateMessageResp response = client(command.credential()).im().v1().message().create(request);
            if (!response.success()) {
                throw new IllegalStateException("feishu send interactive card failed: code="
                    + response.getCode()
                    + ", msg="
                    + response.getMsg()
                    + ", requestId="
                    + response.getRequestId());
            }
            return new FeishuSendInteractiveCardResult(
                response.getData() == null ? null : response.getData().getMessageId(),
                metadata(response.getRequestId())
            );
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to send feishu interactive card", error);
        }
    }

    @Override
    public void deleteMessage(FeishuDeleteMessageCommand command) {
        try {
            DeleteMessageReq request = DeleteMessageReq.newBuilder()
                .messageId(command.externalMessageId())
                .build();
            DeleteMessageResp response = client(command.credential()).im().v1().message().delete(request);
            if (!response.success()) {
                throw new IllegalStateException(feishuError("feishu delete message failed", response.getCode(), response.getMsg(), response.getRequestId()));
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to delete feishu message", error);
        }
    }

    @Override
    public void updateCardText(FeishuUpdateCardTextCommand command) {
        try {
            ContentCardElementReq request = ContentCardElementReq.newBuilder()
                .cardId(command.cardId())
                .elementId(command.elementId())
                .contentCardElementReqBody(ContentCardElementReqBody.newBuilder()
                    .uuid(command.uuid())
                    .content(command.content())
                    .sequence(command.sequence())
                    .build())
                .build();
            ContentCardElementResp response = client(command.credential()).cardkit().v1().cardElement().content(request);
            if (!response.success()) {
                throw new IllegalStateException(feishuError("feishu update card text failed", response.getCode(), response.getMsg(), response.getRequestId()));
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to update feishu card text", error);
        }
    }

    @Override
    public void updateCardSettings(FeishuUpdateCardSettingsCommand command) {
        try {
            SettingsCardReq request = SettingsCardReq.newBuilder()
                .cardId(command.cardId())
                .settingsCardReqBody(SettingsCardReqBody.newBuilder()
                    .uuid(command.uuid())
                    .settings(command.settings())
                    .sequence(command.sequence())
                    .build())
                .build();
            SettingsCardResp response = client(command.credential()).cardkit().v1().card().settings(request);
            if (!response.success()) {
                throw new IllegalStateException(feishuError("feishu update card settings failed", response.getCode(), response.getMsg(), response.getRequestId()));
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to update feishu card settings", error);
        }
    }

    private static com.lark.oapi.Client client(FeishuAppCredential credential) {
        return com.lark.oapi.Client.newBuilder(credential.appId(), credential.appSecret()).build();
    }

    private static Map<String, Object> metadata(String requestId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "requestId", requestId);
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static String feishuError(String prefix, int code, String msg, String requestId) {
        return prefix + ": code=" + code + ", msg=" + msg + ", requestId=" + requestId;
    }
}
