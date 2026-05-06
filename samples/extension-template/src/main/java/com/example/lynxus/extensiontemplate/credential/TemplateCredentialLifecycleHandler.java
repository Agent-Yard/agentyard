package com.example.lynxus.extensiontemplate.credential;

import com.example.lynxus.extensiontemplate.protocol.ExtensionApiException;
import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.lynxus.extension.sdk.generated.protocol.model.CreateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.CreateCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.RevokeCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.RevokeCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.RotateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.RotateCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.ValidateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ValidateCredentialResponse;
import org.springframework.stereotype.Service;

@Service
public final class TemplateCredentialLifecycleHandler implements CredentialLifecycleHandler {
    @Override
    public CreateCredentialResponse create(CreateCredentialRequest request, ExtensionRequestContext context) {
        // Store request.credential in your own vault and return only an opaque externalSecretRef.
        throw ExtensionApiException.notImplemented("credential create");
    }

    @Override
    public RotateCredentialResponse rotate(RotateCredentialRequest request, ExtensionRequestContext context) {
        // Replace the vault material behind request.account.externalSecretRef without exposing secrets to Lynxus.
        throw ExtensionApiException.notImplemented("credential rotate");
    }

    @Override
    public RevokeCredentialResponse revoke(RevokeCredentialRequest request, ExtensionRequestContext context) {
        // Revoke/delete the vault material behind request.account.externalSecretRef.
        throw ExtensionApiException.notImplemented("credential revoke");
    }

    @Override
    public ValidateCredentialResponse validate(ValidateCredentialRequest request, ExtensionRequestContext context) {
        // Probe the external provider using the secret referenced by request.account.externalSecretRef.
        throw ExtensionApiException.notImplemented("credential validate");
    }
}
