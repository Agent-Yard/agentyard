package com.example.lynxus.extensiontemplate.credential;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.lynxus.extension.sdk.generated.protocol.model.CreateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.CreateCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.RevokeCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.RevokeCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.RotateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.RotateCredentialResponse;
import com.lynxus.extension.sdk.generated.protocol.model.ValidateCredentialRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ValidateCredentialResponse;

public interface CredentialLifecycleHandler {
    CreateCredentialResponse create(CreateCredentialRequest request, ExtensionRequestContext context);

    RotateCredentialResponse rotate(RotateCredentialRequest request, ExtensionRequestContext context);

    RevokeCredentialResponse revoke(RevokeCredentialRequest request, ExtensionRequestContext context);

    ValidateCredentialResponse validate(ValidateCredentialRequest request, ExtensionRequestContext context);
}
