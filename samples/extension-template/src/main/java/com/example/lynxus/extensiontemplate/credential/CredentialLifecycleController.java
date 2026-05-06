package com.example.lynxus.extensiontemplate.credential;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.example.lynxus.extensiontemplate.protocol.ProtocolHeaderExtractor;
import com.lynxus.extension.sdk.generated.protocol.model.CreateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.CreateCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.RevokeCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.RevokeCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.RotateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.RotateCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.ValidateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ValidateCredentialResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CredentialLifecycleController {
    public static final String CREATE_PATH = "/credentials";
    public static final String ROTATE_PATH = "/credentials/rotate";
    public static final String REVOKE_PATH = "/credentials/revoke";
    public static final String VALIDATE_PATH = "/credentials/validate";

    private final ProtocolHeaderExtractor headerExtractor;
    private final CredentialLifecycleHandler handler;

    public CredentialLifecycleController(ProtocolHeaderExtractor headerExtractor, CredentialLifecycleHandler handler) {
        this.headerExtractor = headerExtractor;
        this.handler = handler;
    }

    @PostMapping(value = CREATE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CreateCredentialResponse create(@RequestBody CreateCredentialRequest requestBody, HttpServletRequest request) {
        ExtensionRequestContext context = headerExtractor.credentialContext(request);
        return handler.create(requestBody, context);
    }

    @PostMapping(value = ROTATE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RotateCredentialResponse rotate(@RequestBody RotateCredentialRequest requestBody, HttpServletRequest request) {
        ExtensionRequestContext context = headerExtractor.credentialContext(request);
        return handler.rotate(requestBody, context);
    }

    @PostMapping(value = REVOKE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RevokeCredentialResponse revoke(@RequestBody RevokeCredentialRequest requestBody, HttpServletRequest request) {
        ExtensionRequestContext context = headerExtractor.credentialContext(request);
        return handler.revoke(requestBody, context);
    }

    @PostMapping(value = VALIDATE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidateCredentialResponse validate(@RequestBody ValidateCredentialRequest requestBody, HttpServletRequest request) {
        ExtensionRequestContext context = headerExtractor.credentialContext(request);
        return handler.validate(requestBody, context);
    }
}
