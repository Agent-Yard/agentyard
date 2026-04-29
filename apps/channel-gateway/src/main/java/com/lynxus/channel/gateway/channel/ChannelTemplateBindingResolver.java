package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingKey;
import com.lynxus.contracts.channel.ChannelContracts.ResolvedChannelTemplate;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ChannelTemplateBindingResolver {
    private final ChannelAdminRepository repository;

    public ChannelTemplateBindingResolver(ChannelAdminRepository repository) {
        this.repository = repository;
    }

    public Optional<ResolvedChannelTemplate> resolve(
        String assistantId,
        String channelProfileId,
        String messageType,
        String messageSubtype,
        String messageVersion
    ) {
        ChannelTemplateBindingKey key = new ChannelTemplateBindingKey(
            requireText(channelProfileId, "channelProfileId"),
            requireText(assistantId, "assistantId"),
            requireText(messageType, "messageType"),
            requireText(messageSubtype, "messageSubtype"),
            requireText(messageVersion, "messageVersion")
        );
        return repository.findTemplateBinding(key)
            .filter(ChannelTemplateBinding::enabled)
            .map(binding -> new ResolvedChannelTemplate(
                binding.externalTemplateId(),
                binding.externalTemplateVersion(),
                binding.variableSchema(),
                binding.displayName(),
                binding.externalEditUrl(),
                binding.revision()
            ));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
