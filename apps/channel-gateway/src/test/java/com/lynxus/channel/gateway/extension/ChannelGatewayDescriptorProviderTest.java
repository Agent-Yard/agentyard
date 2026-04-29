package com.lynxus.channel.gateway.extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.extension.sdk.common.DescriptorDefinitionDigests;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class ChannelGatewayDescriptorProviderTest {
    @Test
    void producesCanonicalValidFeishuManifest() {
        ChannelGatewayDescriptorProvider provider = new ChannelGatewayDescriptorProvider();

        assertTrue(ManifestValidator.validate(provider.manifest()).valid());
        assertTrue(ManifestValidator.validate(provider.manifest(), protocolSchemaDir()).valid());
        assertTrue(ManifestValidator.validateJson(new String(provider.canonicalManifestBytes())).valid());
        assertEquals("feishu", provider.channelProviderDescriptors().getFirst().get("providerType"));
        assertTrue(
            DescriptorDefinitionDigests.channelProviderDefinitionDigest(provider.channelProviderDescriptors().getFirst())
                .startsWith("sha256:")
        );
    }

    @Test
    void httpManifestBytesEqualInternalProviderCanonicalBytes() throws Exception {
        ChannelGatewayDescriptorProvider provider = new ChannelGatewayDescriptorProvider();
        MvcResult result = MockMvcBuilders.standaloneSetup(new ExtensionManifestController(provider))
            .build()
            .perform(get("/extension/manifest"))
            .andExpect(status().isOk())
            .andReturn();

        assertArrayEquals(provider.canonicalManifestBytes(), result.getResponse().getContentAsByteArray());
        Map<String, Object> manifest = JsonDocuments.parseObject(result.getResponse().getContentAsString());
        assertTrue(ManifestValidator.validate(manifest).valid());
    }

    private static Path protocolSchemaDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path schemaDir = current.resolve("packages/extension-protocol/json-schema");
            if (Files.isDirectory(schemaDir)) {
                return schemaDir;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("unable to locate extension protocol schema assets");
    }
}
