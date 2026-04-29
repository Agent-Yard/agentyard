package com.lynxus.channel.gateway.shared;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
