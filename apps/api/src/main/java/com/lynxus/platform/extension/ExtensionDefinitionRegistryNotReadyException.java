package com.lynxus.platform.extension;

import com.lynxus.platform.shared.DownstreamServiceException;
import org.springframework.http.HttpStatus;

public final class ExtensionDefinitionRegistryNotReadyException extends DownstreamServiceException {
    public ExtensionDefinitionRegistryNotReadyException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Extension definition registry is NOT_READY");
    }
}
