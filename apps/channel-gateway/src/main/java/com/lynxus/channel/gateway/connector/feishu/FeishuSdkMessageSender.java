package com.lynxus.channel.gateway.connector.feishu;

import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
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
    public FeishuSendTextResult sendText(FeishuSendTextCommand command) {
        try {
            com.lark.oapi.Client client = com.lark.oapi.Client.newBuilder(
                command.credential().appId(),
                command.credential().appSecret()
            ).build();
            CreateMessageReq request = CreateMessageReq.newBuilder()
                .receiveIdType(command.receiveIdType())
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                    .receiveId(command.receiveId())
                    .msgType("text")
                    .content(objectMapper.writeValueAsString(Map.of("text", command.text())))
                    .uuid(command.uuid())
                    .build())
                .build();
            CreateMessageResp response = client.im().v1().message().create(request);
            if (!response.success()) {
                throw new IllegalStateException("feishu send text message failed: code="
                    + response.getCode()
                    + ", msg="
                    + response.getMsg()
                    + ", requestId="
                    + response.getRequestId());
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("receiveIdType", command.receiveIdType());
            metadata.put("requestId", response.getRequestId());
            return new FeishuSendTextResult(
                response.getData() == null ? null : response.getData().getMessageId(),
                metadata
            );
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to send feishu text message", error);
        }
    }
}
