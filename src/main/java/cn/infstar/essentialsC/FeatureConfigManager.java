package cn.infstar.essentialsC;

import cn.infstar.essentialsC.util.AtomicYamlWriter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 管理主配置与独立功能配置，并负责按顺序执行配置迁移。
 */
public final class FeatureConfigManager {

    private static final int MAIN_CONFIG_VERSION = 3;

    private final EssentialsC plugin;
    private final File mainConfigFile;
    private final File blocksMenuFile;
    private FileConfiguration blocksMenuConfig;

    public FeatureConfigManager(EssentialsC plugin) {
        this.plugin = plugin;
        this.mainConfigFile = new File(plugin.getDataFolder(), "config.yml");
        this.blocksMenuFile = new File(plugin.getDataFolder(), "blocks-menu.yml");
        reload();
    }

    public void reload() {
        ensureResource(mainConfigFile, "config.yml");
        ensureResource(blocksMenuFile, "blocks-menu.yml");

        blocksMenuConfig = loadWithDefaults(blocksMenuFile, "blocks-menu.yml");
        YamlConfiguration loadedMainConfig = loadWithDefaults(mainConfigFile, "config.yml");
        YamlConfiguration mainConfig = migrateMainConfigVersion(loadedMainConfig);

        boolean mainChanged = mainConfig != loadedMainConfig;
        mainChanged |= migrateLegacyMainConfig(mainConfig);
        mainChanged |= migrateLegacyDebugSettings(mainConfig);
        mainChanged |= removeRetiredJeiSettings(mainConfig);
        mainChanged |= removeRetiredSkinProviders(mainConfig);
        if (mainConfig.getInt("config-version", 0) != MAIN_CONFIG_VERSION) {
            mainConfig.set("config-version", MAIN_CONFIG_VERSION);
            mainChanged = true;
        }

        if (mainChanged) {
            save(mainConfig, mainConfigFile);
        }
        plugin.reloadConfig();
    }

    public FileConfiguration getBlocksMenuConfig() {
        return blocksMenuConfig;
    }

    public void saveBlocksMenuConfig() {
        save(blocksMenuConfig, blocksMenuFile);
    }

    public boolean updateMainConfigValue(String path, Object value) {
        YamlConfiguration mainConfig = loadWithDefaults(mainConfigFile, "config.yml");
        mainConfig.set(path, value);
        if (!save(mainConfig, mainConfigFile)) {
            return false;
        }
        plugin.getConfig().set(path, value);
        return true;
    }

    private YamlConfiguration migrateMainConfigVersion(YamlConfiguration existingConfig) {
        int existingVersion = existingConfig.getInt("config-version", 0);
        if (existingVersion >= MAIN_CONFIG_VERSION) {
            return existingConfig;
        }

        File backupFile = new File(plugin.getDataFolder(),
            "config.v" + existingVersion + ".bak-" + System.currentTimeMillis() + ".yml");
        try {
            Files.copy(mainConfigFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            YamlConfiguration migratedConfig = loadResource("config.yml");
            if (migratedConfig == null) {
                existingConfig.set("config-version", MAIN_CONFIG_VERSION);
                return existingConfig;
            }

            for (String path : existingConfig.getKeys(true)) {
                boolean legacyFeaturePath = path.startsWith("skin-bridge.") || path.startsWith("blocks-menu.");
                if (!existingConfig.isConfigurationSection(path)
                    && (migratedConfig.contains(path) || legacyFeaturePath)) {
                    migratedConfig.set(path, existingConfig.get(path));
                }
            }
            migratedConfig.set("config-version", MAIN_CONFIG_VERSION);
            plugin.getLogger().info("已将 config.yml 从版本 " + existingVersion
                + " 迁移到 " + MAIN_CONFIG_VERSION + "，备份文件: " + backupFile.getName());
            return migratedConfig;
        } catch (IOException exception) {
            plugin.getLogger().severe("迁移 config.yml 失败: " + exception.getMessage());
            return existingConfig;
        }
    }

    private boolean migrateLegacyMainConfig(FileConfiguration mainConfig) {
        if (!mainConfig.contains("blocks-menu", true)) {
            return false;
        }

        copySection(mainConfig.getConfigurationSection("blocks-menu"), blocksMenuConfig);
        mainConfig.set("blocks-menu", null);
        saveBlocksMenuConfig();
        plugin.getLogger().info("已将便捷菜单配置迁移到 blocks-menu.yml。");
        return true;
    }

    private boolean migrateLegacyDebugSettings(FileConfiguration mainConfig) {
        boolean hasJeiDebug = mainConfig.contains("jei-sync.debug", true);
        boolean hasSkinBridgeDebug = mainConfig.contains("skin-bridge.debug", true);
        if (!hasJeiDebug && !hasSkinBridgeDebug) {
            return false;
        }

        boolean debugEnabled = mainConfig.getBoolean("debug", false)
            || mainConfig.getBoolean("jei-sync.debug", false)
            || mainConfig.getBoolean("skin-bridge.debug", false);
        mainConfig.set("debug", debugEnabled);
        mainConfig.set("jei-sync.debug", null);
        mainConfig.set("skin-bridge.debug", null);
        plugin.getLogger().info("已将独立功能调试开关合并到 config.yml 的全局 debug。");
        return true;
    }

    private boolean removeRetiredJeiSettings(FileConfiguration mainConfig) {
        if (!mainConfig.contains("jei-sync", true)) {
            return false;
        }

        mainConfig.set("jei-sync", null);
        plugin.getLogger().info("已从 config.yml 移除停用的 JEI 配方同步配置。");
        return true;
    }

    private boolean removeRetiredSkinProviders(FileConfiguration mainConfig) {
        ConfigurationSection providers = mainConfig.getConfigurationSection("skin-bridge.providers");
        if (providers == null) {
            return false;
        }

        boolean changed = false;
        for (String providerId : providers.getKeys(false)) {
            if (!"littleskin".equalsIgnoreCase(providerId)) {
                continue;
            }
            mainConfig.set("skin-bridge.providers." + providerId, null);
            changed = true;
            plugin.getLogger().info("已从 config.yml 移除停用的 LittleSkin Provider 配置。");
        }
        return changed;
    }

    private void copySection(ConfigurationSection source, FileConfiguration target) {
        if (source == null) {
            return;
        }
        for (String path : source.getKeys(true)) {
            if (!source.isConfigurationSection(path)) {
                target.set(path, source.get(path));
            }
        }
    }

    private YamlConfiguration loadWithDefaults(File file, String resourcePath) {
        YamlConfiguration config = loadFile(file);
        YamlConfiguration defaults = loadResource(resourcePath);
        if (defaults != null) {
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
        }
        return config;
    }

    private YamlConfiguration loadFile(File file) {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("加载 " + file.getName() + " 失败: " + exception.getMessage());
            throw new IllegalStateException("无法加载 " + file.getName() + "，请修复配置格式后重试。", exception);
        }
        return config;
    }

    private YamlConfiguration loadResource(String resourcePath) {
        InputStream resource = plugin.getResource(resourcePath);
        if (resource == null) {
            return null;
        }

        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        try (InputStream input = resource;
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            config.load(reader);
            return config;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("加载内置资源 " + resourcePath + " 失败: " + exception.getMessage());
            return null;
        }
    }

    private void ensureResource(File file, String resourcePath) {
        if (!file.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    private boolean save(FileConfiguration config, File file) {
        try {
            AtomicYamlWriter.save(config, file);
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("保存 " + file.getName() + " 失败: " + exception.getMessage());
            return false;
        }
    }
}
