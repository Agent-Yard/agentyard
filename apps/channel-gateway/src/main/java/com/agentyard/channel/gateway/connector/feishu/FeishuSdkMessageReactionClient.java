package com.agentyard.channel.gateway.connector.feishu;

import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionResp;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionReq;
import com.lark.oapi.service.im.v1.model.DeleteMessageReactionResp;
import com.lark.oapi.service.im.v1.model.Emoji;
import org.springframework.stereotype.Component;

@Component
final class FeishuSdkMessageReactionClient implements FeishuMessageReactionClient {
    @Override
    public FeishuAddReactionResult addReaction(FeishuAddReactionCommand command) {
        try {
            com.lark.oapi.Client client = com.lark.oapi.Client.newBuilder(
                command.credential().appId(),
                command.credential().appSecret()
            ).build();
            CreateMessageReactionReq request = CreateMessageReactionReq.newBuilder()
                .messageId(command.messageId())
                .createMessageReactionReqBody(CreateMessageReactionReqBody.newBuilder()
                    .reactionType(Emoji.newBuilder()
                        .emojiType(command.emojiType())
                        .build())
                    .build())
                .build();
            CreateMessageReactionResp response = client.im().v1().messageReaction().create(request);
            if (!response.success()) {
                throw new IllegalStateException("feishu add message reaction failed: code="
                    + response.getCode()
                    + ", msg="
                    + response.getMsg()
                    + ", requestId="
                    + response.getRequestId());
            }
            String reactionId = response.getData() == null ? null : response.getData().getReactionId();
            if (reactionId == null || reactionId.isBlank()) {
                throw new IllegalStateException("feishu add message reaction returned blank reactionId");
            }
            return new FeishuAddReactionResult(reactionId);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to add feishu message reaction", error);
        }
    }

    @Override
    public void deleteReaction(FeishuDeleteReactionCommand command) {
        try {
            com.lark.oapi.Client client = com.lark.oapi.Client.newBuilder(
                command.credential().appId(),
                command.credential().appSecret()
            ).build();
            DeleteMessageReactionReq request = DeleteMessageReactionReq.newBuilder()
                .messageId(command.messageId())
                .reactionId(command.reactionId())
                .build();
            DeleteMessageReactionResp response = client.im().v1().messageReaction().delete(request);
            if (!response.success()) {
                throw new IllegalStateException("feishu delete message reaction failed: code="
                    + response.getCode()
                    + ", msg="
                    + response.getMsg()
                    + ", requestId="
                    + response.getRequestId());
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to delete feishu message reaction", error);
        }
    }
}
