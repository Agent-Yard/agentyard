package com.lynxus.channel.gateway.channel;

final class ChannelInboundSessionRejectedException extends RuntimeException {
    ChannelInboundSessionRejectedException(String message) {
        super(message);
    }
}
