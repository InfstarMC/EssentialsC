package cn.infstar.essentialsC;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationResourcesTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "config.yml",
        "modules.yml",
        "blocks-menu.yml",
        "paper-plugin.yml",
        "lang/zh_CN.yml",
        "lang/en_US.yml"
    })
    void bundledYamlIsValid(String resourcePath) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(input, resourcePath);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));

            if (resourcePath.equals("config.yml")) {
                assertEquals(3, configuration.getInt("config-version"));
                assertEquals(5, configuration.getInt("tpa.max-pending-requests"));
                assertEquals(500, configuration.getInt("skin-bridge.max-generated-cache-entries"));
                assertTrue(configuration.getString("skin-bridge.mineskin.api-key", "").isBlank());
                assertFalse(configuration.contains("skin-bridge.providers.littleskin", true));
            }
            if (resourcePath.equals("paper-plugin.yml")) {
                assertEquals("1.21.11", configuration.getString("api-version"));
            }
            if (resourcePath.startsWith("lang/")) {
                assertTrue(!configuration.getString("prefix", "").isBlank());
            }
        }
    }

    @Test
    void bundledLanguagesContainTheSameKeys() throws Exception {
        YamlConfiguration chinese = loadResource("lang/zh_CN.yml");
        YamlConfiguration english = loadResource("lang/en_US.yml");

        assertEquals(chinese.getKeys(true), english.getKeys(true));
    }

    @Test
    void retiredJeiFeatureIsAbsentFromResources() throws Exception {
        assertFalse(loadResource("config.yml").contains("jei-sync", true));
        assertFalse(loadResource("modules.yml").contains("modules.jei-sync", true));
        assertFalse(loadResource("lang/zh_CN.yml").contains("messages.jei-sync-fabric", true));
        assertFalse(loadResource("lang/zh_CN.yml").contains("messages.jei-sync-neoforge", true));
        assertFalse(loadResource("lang/en_US.yml").contains("messages.jei-sync-fabric", true));
        assertFalse(loadResource("lang/en_US.yml").contains("messages.jei-sync-neoforge", true));
    }

    @Test
    void retiredMaintenanceFeatureIsAbsentFromResources() throws Exception {
        assertNull(getClass().getClassLoader().getResource("maintenance.yml"));
        assertFalse(loadResource("modules.yml").contains("modules.maintenance", true));
        assertFalse(loadResource("paper-plugin.yml").contains("dependencies.server.LuckPerms", true));
        assertFalse(loadResource("paper-plugin.yml").contains("permissions.essentialsc.command.maintenance", true));
        assertFalse(loadResource("paper-plugin.yml").contains("permissions.essentialsc.maintenance.bypass", true));
        assertFalse(loadResource("paper-plugin.yml").contains("permissions.essentialsc.maintenance.notify", true));
        assertFalse(loadResource("lang/zh_CN.yml").contains("maintenance", true));
        assertFalse(loadResource("lang/en_US.yml").contains("maintenance", true));
        assertNull(cn.infstar.essentialsC.commands.CommandRegistry.resolveCommandName("maintenance"));
        assertNull(cn.infstar.essentialsC.commands.CommandRegistry.resolveCommandName("maint"));
    }

    @Test
    void miniMessageTagNamesAreNormalized() {
        assertEquals("<gray>文本</gray>", LangManager.normalizeMiniMessageTags("<GRAY>文本</GRAY>"));
        assertEquals("\\<GRAY>", LangManager.normalizeMiniMessageTags("\\<GRAY>"));
    }

    @Test
    void legacyFormattingCanBeNestedInMiniMessage() {
        String message = LangManager.applyPlaceholders(
            "<gray>维护状态: {status}</gray>",
            java.util.Map.of("status", "§a开启")
        );
        String rendered = PlainTextComponentSerializer.plainText().serialize(
            MiniMessage.miniMessage().deserialize(message)
        );

        assertEquals("维护状态: 开启", rendered);
    }

    private YamlConfiguration loadResource(String resourcePath) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(input, resourcePath);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return configuration;
        }
    }
}
