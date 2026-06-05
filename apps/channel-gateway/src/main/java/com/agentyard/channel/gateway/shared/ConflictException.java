package com.agentyard.channel.gateway.shared;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
