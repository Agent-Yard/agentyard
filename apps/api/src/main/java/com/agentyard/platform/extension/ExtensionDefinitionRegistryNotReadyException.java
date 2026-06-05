package com.agentyard.platform.extension;

import com.agentyard.platform.shared.DownstreamServiceException;
import org.springframework.http.HttpStatus;

public final class ExtensionDefinitionRegistryNotReadyException extends DownstreamServiceException {
    public ExtensionDefinitionRegistryNotReadyException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Extension definition registry is NOT_READY");
    }
}
