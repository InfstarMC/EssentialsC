package cn.infstar.essentialsC.skinbridge;

import cn.infstar.essentialsC.EssentialsC;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class SkinBridgeManager implements Listener {

    private static final int WORKER_THREADS = 2;
    private static final int MAX_PENDING_LOOKUPS = 100;

    private final EssentialsC plugin;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor executor;
    private final SkinCacheStore cacheStore;
    private final ConcurrentMap<UUID, CachedLookup> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<SkinCacheStore.Key, SkinCacheStore.Entry> generatedSkinCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> pendingLookups = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BukkitRunnable> scheduledLookups = new ConcurrentHashMap<>();
    private final ConcurrentMap<LookupKey, Future<?>> runningLookups = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> forceRefreshCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> loginSkinUrls = new ConcurrentHashMap<>();
    private final ConcurrentMap<SkinCacheStore.Key, CompletableFuture<GeneratedSkin>> pendingGenerations = new ConcurrentHashMap<>();
    private final AtomicLong configurationGeneration = new AtomicLong();
    private BukkitTask generatedCacheSaveTask;
    private boolean generatedCacheDirty;
    private volatile boolean shuttingDown;

    private volatile List<SkinProvider> providers = List.of();
    private volatile SkinBridgeGateway gateway;
    private volatile boolean debug;
    private volatile boolean sendPlayerMessage;
    private volatile boolean logDetectionResults;
    private volatile int requestTimeoutSeconds;
    private volatile int cacheMinutes;
    private volatile int maxGeneratedCacheEntries;
    private volatile int forceRefreshCooldownSeconds;
    private volatile boolean requireCurrentTextureMatch;
    private volatile long joinDelayTicks;
    private volatile Set<String> excludedUuids = Set.of();
    private volatile Set<String> excludedNames = Set.of();

    public SkinBridgeManager(EssentialsC plugin) {
        this.plugin = plugin;
        this.cacheStore = new SkinCacheStore(plugin);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.executor = new ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_LOOKUPS),
            new SkinBridgeThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
        reload();
        loadGeneratedSkinCache();
    }

    public void reload() {
        configurationGeneration.incrementAndGet();
        cancelScheduledLookups();
        cancelRunningLookups();
        pendingLookups.clear();
        pendingGenerations.clear();
        FileConfiguration config = plugin.getConfig();

        debug = plugin.getConfig().getBoolean("debug", false);
        sendPlayerMessage = config.getBoolean("skin-bridge.send-player-message", true);
        logDetectionResults = config.getBoolean("skin-bridge.log-detection-results", true);
        requestTimeoutSeconds = clamp(config.getInt("skin-bridge.profile-request-timeout-seconds", 5), 1, 30);
        String mineSkinEndpoint = config.getString("skin-bridge.mineskin.endpoint", "https://api.mineskin.org");
        String mineSkinApiKey = config.getString("skin-bridge.mineskin.api-key", "").trim();
        String mineSkinVisibility = config.getString("skin-bridge.mineskin.visibility", "unlisted");
        int mineSkinTimeoutSeconds = clamp(config.getInt("skin-bridge.mineskin.request-timeout-seconds", 30), 10, 180);
        long minimumSubmitIntervalMillis = clamp(
            config.getLong("skin-bridge.mineskin.minimum-submit-interval-millis", 1000L), 0L, 10000L);
        cacheMinutes = clamp(config.getInt("skin-bridge.cache-minutes", 120), 5, 10080);
        maxGeneratedCacheEntries = clamp(config.getInt("skin-bridge.max-generated-cache-entries", 500), 10, 10_000);
        forceRefreshCooldownSeconds = clamp(config.getInt("skin-bridge.force-refresh-cooldown-seconds", 30), 0, 3600);
        requireCurrentTextureMatch = config.getBoolean("skin-bridge.require-current-texture-match", true);
        joinDelayTicks = clamp(config.getLong("skin-bridge.join-delay-ticks", 20L), 0, 200);
        excludedUuids = loadNormalizedValues(config, "skin-bridge.exclusions.uuids");
        excludedNames = loadNormalizedValues(config, "skin-bridge.exclusions.names");
        providers = loadProviders(config);
        cache.clear();
        gateway = loadGateway(mineSkinEndpoint, mineSkinApiKey, mineSkinVisibility,
            mineSkinTimeoutSeconds, minimumSubmitIntervalMillis);

        if (gateway == null) {
            plugin.getLogger().warning("SkinBridge 已启用，但未配置有效的 MineSkin API Key，皮肤同步不会执行。");
        }
    }

    public void shutdown() {
        shuttingDown = true;
        configurationGeneration.incrementAndGet();
        cancelScheduledLookups();
        cancelRunningLookups();
        executor.shutdownNow();
        cancelGeneratedCacheSave();
        saveGeneratedSkinCache();
        cache.clear();
        generatedSkinCache.clear();
        pendingLookups.clear();
        pendingGenerations.clear();
        forceRefreshCooldowns.clear();
        loginSkinUrls.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        getCurrentSkinUrl(event.getPlayer()).ifPresentOrElse(
            skinUrl -> loginSkinUrls.put(playerId, skinUrl),
            () -> loginSkinUrls.remove(playerId)
        );
        queueSync(event.getPlayer(), false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        loginSkinUrls.remove(playerId);
        pendingLookups.remove(playerId);
        cancelScheduledLookup(playerId);
        cancelRunningLookup(playerId);
    }

    public SyncResult queueSync(Player player, boolean force) {
        UUID playerId = player.getUniqueId();
        if (isExcluded(player)) {
            cache.put(playerId, cached(null, null, State.EXCLUDED));
            sendPlayerNotification(playerId, "skin-bridge.notifications.excluded", Map.of());
            if (logDetectionResults) {
                plugin.getLogger().info("SkinBridge 已根据排除名单跳过玩家: " + player.getName());
            }
            return SyncResult.EXCLUDED;
        }
        if (gateway == null) {
            return SyncResult.DEPENDENCY_MISSING;
        }
        if (providers.isEmpty()) {
            return SyncResult.NO_PROVIDERS;
        }

        if (force) {
            long now = System.currentTimeMillis();
            long expiresAt = forceRefreshCooldowns.getOrDefault(playerId, 0L);
            if (expiresAt > now) {
                return SyncResult.REFRESH_COOLDOWN;
            }
            cache.remove(playerId);
        }

        CachedLookup cached = cache.get(playerId);
        if (!force && cached != null && !cached.hasExpired()) {
            if (cached.skin() != null) {
                applySkin(playerId, cached, configurationGeneration.get());
            }
            return SyncResult.CACHED;
        }

        long lookupGeneration = configurationGeneration.get();
        if (!pendingLookups.containsKey(playerId) && pendingLookups.size() >= MAX_PENDING_LOOKUPS) {
            plugin.getLogger().warning("SkinBridge 查询队列已满，已跳过玩家: " + player.getName());
            sendPlayerNotification(playerId, "skin-bridge.notifications.queue-full", Map.of());
            return SyncResult.QUEUE_FULL;
        }
        if (!registerPendingLookup(playerId, lookupGeneration)) {
            return SyncResult.ALREADY_RUNNING;
        }
        if (force && forceRefreshCooldownSeconds > 0) {
            forceRefreshCooldowns.put(playerId,
                System.currentTimeMillis() + forceRefreshCooldownSeconds * 1000L);
        }

        String playerName = player.getName();
        String loginSkinUrl = loginSkinUrls.computeIfAbsent(playerId,
            ignored -> getCurrentSkinUrl(player).orElse(""));
        String currentSkinUrl = loginSkinUrl.isEmpty() ? null : loginSkinUrl;
        BukkitRunnable scheduledLookup = new BukkitRunnable() {
            @Override
            public void run() {
                scheduledLookups.remove(playerId, this);
                startLookup(playerId, playerName, currentSkinUrl, lookupGeneration);
            }
        };
        BukkitRunnable previousLookup = scheduledLookups.put(playerId, scheduledLookup);
        if (previousLookup != null) {
            previousLookup.cancel();
        }
        scheduledLookup.runTaskLater(plugin, joinDelayTicks);
        sendPlayerNotification(playerId, "skin-bridge.notifications.detecting", Map.of());
        return SyncResult.QUEUED;
    }

    public Status getStatus(Player player) {
        CachedLookup cached = cache.get(player.getUniqueId());
        if (cached != null && !cached.hasExpired()) {
            return new Status(cached.state(), cached.providerId());
        }
        if (pendingLookups.containsKey(player.getUniqueId())) {
            return new Status(State.PENDING, null);
        }
        return new Status(State.UNKNOWN, null);
    }

    public int getRemainingForceRefreshCooldownSeconds(Player player) {
        long remaining = forceRefreshCooldowns.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
        return remaining <= 0L ? 0 : (int) Math.ceil(remaining / 1000.0D);
    }

    public boolean isSkinGatewayAvailable() {
        return gateway != null;
    }

    public int getProviderCount() {
        return providers.size();
    }

    public String getModuleDetail() {
        if (gateway == null) {
            return "缺少 MineSkin API Key 或配置无效";
        }
        if (providers.isEmpty()) {
            return "未配置有效 Provider";
        }
        return providers.size() + " 个 Provider 已就绪";
    }

    private void startLookup(UUID playerId, String playerName, String currentSkinUrl, long lookupGeneration) {
        Player player = Bukkit.getPlayer(playerId);
        if (executor.isShutdown() || lookupGeneration != configurationGeneration.get()
            || !isLookupActive(playerId, lookupGeneration) || player == null || !player.isOnline()) {
            pendingLookups.remove(playerId, lookupGeneration);
            return;
        }
        LookupKey lookupKey = new LookupKey(playerId, lookupGeneration);
        try {
            Future<?> lookupTask = executor.submit(() -> {
                try {
                    CachedLookup resolved = resolve(playerId, playerName, currentSkinUrl);
                    if (lookupGeneration != configurationGeneration.get() || !isLookupActive(playerId, lookupGeneration)) {
                        return;
                    }
                    cache.put(playerId, resolved);
                    if (resolved.skin() != null) {
                        applySkin(playerId, resolved, lookupGeneration);
                    } else {
                        sendPlayerNotification(playerId, "skin-bridge.notifications.not-external", Map.of());
                        if (logDetectionResults) {
                            plugin.getLogger().info("SkinBridge 未匹配到外置皮肤站，已保留玩家皮肤: " + playerName);
                        }
                    }
                } catch (Exception exception) {
                    if (!isLookupActive(playerId, lookupGeneration) || exception instanceof InterruptedException) {
                        return;
                    }
                    plugin.getLogger().warning("SkinBridge 查询 " + playerName + " 的皮肤资料失败: " + exception.getMessage());
                    sendPlayerNotification(playerId, "skin-bridge.notifications.failed", Map.of());
                    if (debug) {
                        plugin.getLogger().warning("SkinBridge 异常类型: " + exception.getClass().getName());
                    }
                } finally {
                    pendingLookups.remove(playerId, lookupGeneration);
                    runningLookups.remove(lookupKey);
                }
            });
            runningLookups.put(lookupKey, lookupTask);
            if (!isLookupActive(playerId, lookupGeneration)) {
                runningLookups.remove(lookupKey, lookupTask);
                lookupTask.cancel(true);
                executor.purge();
            }
        } catch (RejectedExecutionException exception) {
            pendingLookups.remove(playerId, lookupGeneration);
            plugin.getLogger().warning("SkinBridge 查询队列拒绝了玩家任务: " + playerName);
            sendPlayerNotification(playerId, "skin-bridge.notifications.queue-full", Map.of());
        }
    }

    private boolean registerPendingLookup(UUID playerId, long lookupGeneration) {
        while (true) {
            Long runningGeneration = pendingLookups.putIfAbsent(playerId, lookupGeneration);
            if (runningGeneration == null) {
                return true;
            }
            if (runningGeneration == lookupGeneration) {
                return false;
            }
            if (pendingLookups.replace(playerId, runningGeneration, lookupGeneration)) {
                return true;
            }
        }
    }

    private CachedLookup resolve(UUID playerId, String playerName, String currentSkinUrl) throws Exception {
        Exception lastFailure = null;
        for (SkinProvider provider : providers) {
            Optional<ProviderProfile> profile;
            try {
                profile = queryProfile(provider, playerId, playerName);
            } catch (Exception exception) {
                lastFailure = exception;
                plugin.getLogger().warning("SkinBridge Provider " + provider.id() + " 查询失败: " + exception.getMessage());
                continue;
            }
            if (profile.isEmpty()) {
                continue;
            }

            SkinBridgeGateway currentGateway = gateway;
            if (currentGateway == null) {
                throw new IllegalStateException("MineSkin 网关在查询期间不可用。");
            }

            ProviderProfile matchedProfile = profile.get();
            if (requireCurrentTextureMatch
                && (currentSkinUrl == null || !currentSkinUrl.equals(matchedProfile.skinUrl()))) {
                if (debug) {
                    plugin.getLogger().info("SkinBridge 已忽略与当前登录纹理不一致的 Provider: " + provider.name());
                }
                continue;
            }
            if (logDetectionResults) {
                plugin.getLogger().info("SkinBridge 已识别玩家 " + playerName + " 的皮肤来源: " + provider.name());
            }
            SkinCacheStore.Key cacheKey = new SkinCacheStore.Key(matchedProfile.skinUrl(), matchedProfile.model());
            GeneratedSkin generatedSkin = getOrGenerateSkin(cacheKey, currentGateway);
            return cached(provider.name(), generatedSkin, State.EXTERNAL);
        }

        if (lastFailure != null) {
            throw new IllegalStateException("所有可用 Provider 均未能完成确认。", lastFailure);
        }
        return cached(null, null, State.NOT_EXTERNAL);
    }

    private Optional<ProviderProfile> queryProfile(SkinProvider provider, UUID playerId, String playerName) throws Exception {
        URI requestUri = URI.create(provider.resolveProfileUrl(playerId));
        HttpRequest request = HttpRequest.newBuilder(requestUri)
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Accept", "application/json")
            .header("User-Agent", "EssentialsC/" + plugin.getPluginMeta().getVersion() + " SkinBridge")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(provider.id() + " 返回 HTTP " + response.statusCode());
        }
        if (response.body().length() > 1_048_576) {
            throw new IllegalStateException(provider.id() + " 返回的 profile 超过 1 MiB 限制。");
        }

        JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
        String profileId = requireString(profile, "id");
        String profileName = requireString(profile, "name");
        if (!normalizeUuid(profileId).equals(normalizeUuid(playerId.toString())) || !profileName.equalsIgnoreCase(playerName)) {
            if (debug) {
                plugin.getLogger().warning("SkinBridge 忽略 " + provider.id() + " 的不匹配 profile: " + profileName + " / " + profileId);
            }
            return Optional.empty();
        }

        JsonObject textureData = findTextureData(profile);
        JsonObject skin = textureData.getAsJsonObject("textures").getAsJsonObject("SKIN");
        String skinUrl = requireString(skin, "url");
        URI skinUri = URI.create(skinUrl);
        if (!"https".equalsIgnoreCase(skinUri.getScheme())) {
            throw new IllegalStateException(provider.id() + " 返回了非 HTTPS 皮肤 URL。");
        }

        SkinModel model = SkinModel.CLASSIC;
        JsonObject metadata = skin.has("metadata") && skin.get("metadata").isJsonObject()
            ? skin.getAsJsonObject("metadata")
            : null;
        if (metadata != null && "slim".equalsIgnoreCase(metadata.has("model") ? metadata.get("model").getAsString() : "")) {
            model = SkinModel.SLIM;
        }
        return Optional.of(new ProviderProfile(skinUrl, model));
    }

    private JsonObject findTextureData(JsonObject profile) {
        JsonArray properties = profile.has("properties") && profile.get("properties").isJsonArray()
            ? profile.getAsJsonArray("properties")
            : new JsonArray();
        for (JsonElement element : properties) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.has("name") ? property.get("name").getAsString() : "")) {
                continue;
            }
            String encodedValue = requireString(property, "value");
            String decodedValue = new String(Base64.getDecoder().decode(encodedValue), StandardCharsets.UTF_8);
            JsonObject textureData = JsonParser.parseString(decodedValue).getAsJsonObject();
            if (textureData.has("textures")
                && textureData.get("textures").isJsonObject()
                && textureData.getAsJsonObject("textures").has("SKIN")
                && textureData.getAsJsonObject("textures").get("SKIN").isJsonObject()) {
                return textureData;
            }
        }
        throw new IllegalStateException("profile 不包含有效的皮肤 textures 属性。");
    }

    private Optional<String> getCurrentSkinUrl(Player player) {
        try {
            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if (!"textures".equals(property.getName())) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8);
                JsonObject textureData = JsonParser.parseString(decoded).getAsJsonObject();
                if (!textureData.has("textures") || !textureData.get("textures").isJsonObject()) {
                    continue;
                }
                JsonObject textures = textureData.getAsJsonObject("textures");
                if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) {
                    continue;
                }
                String skinUrl = requireString(textures.getAsJsonObject("SKIN"), "url");
                if ("https".equalsIgnoreCase(URI.create(skinUrl).getScheme())) {
                    return Optional.of(skinUrl);
                }
            }
        } catch (RuntimeException exception) {
            if (debug) {
                plugin.getLogger().warning("SkinBridge 无法解析玩家当前纹理: " + exception.getMessage());
            }
        }
        return Optional.empty();
    }

    private void loadGeneratedSkinCache() {
        generatedSkinCache.putAll(cacheStore.load());
        trimGeneratedSkinCache();
    }

    private synchronized void saveGeneratedSkinCache() {
        if (!generatedCacheDirty) {
            return;
        }
        long now = System.currentTimeMillis();
        generatedSkinCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        trimGeneratedSkinCache();
        if (cacheStore.save(generatedSkinCache)) {
            generatedCacheDirty = false;
        } else if (!shuttingDown) {
            scheduleGeneratedCacheSave();
        }
    }

    private GeneratedSkin getOrGenerateSkin(SkinCacheStore.Key cacheKey, SkinBridgeGateway currentGateway) throws Exception {
        SkinCacheStore.Entry cachedEntry = generatedSkinCache.get(cacheKey);
        if (cachedEntry != null && !cachedEntry.hasExpired()) {
            return cachedEntry.skin();
        }
        if (cachedEntry != null) {
            generatedSkinCache.remove(cacheKey, cachedEntry);
        }

        CompletableFuture<GeneratedSkin> created = new CompletableFuture<>();
        CompletableFuture<GeneratedSkin> running = pendingGenerations.putIfAbsent(cacheKey, created);
        if (running != null) {
            return awaitGeneratedSkin(running);
        }

        try {
            GeneratedSkin generated = currentGateway.generateSkin(cacheKey.skinUrl(), cacheKey.model());
            cacheGeneratedSkin(cacheKey, generated);
            created.complete(generated);
            return generated;
        } catch (Exception exception) {
            created.completeExceptionally(exception);
            throw exception;
        } catch (Error error) {
            created.completeExceptionally(error);
            throw error;
        } finally {
            pendingGenerations.remove(cacheKey, created);
        }
    }

    private GeneratedSkin awaitGeneratedSkin(CompletableFuture<GeneratedSkin> running) throws Exception {
        try {
            return running.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MineSkin 生成任务失败。", cause);
        }
    }

    private synchronized void cacheGeneratedSkin(SkinCacheStore.Key cacheKey, GeneratedSkin generatedSkin) {
        if (shuttingDown) {
            return;
        }
        generatedSkinCache.put(cacheKey, new SkinCacheStore.Entry(generatedSkin,
            System.currentTimeMillis() + Duration.ofMinutes(cacheMinutes).toMillis()));
        trimGeneratedSkinCache();
        generatedCacheDirty = true;
        scheduleGeneratedCacheSave();
    }

    private synchronized void scheduleGeneratedCacheSave() {
        if (shuttingDown || generatedCacheSaveTask != null) {
            return;
        }
        generatedCacheSaveTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            synchronized (this) {
                generatedCacheSaveTask = null;
            }
            saveGeneratedSkinCache();
        }, 100L);
    }

    private synchronized void cancelGeneratedCacheSave() {
        if (generatedCacheSaveTask != null) {
            generatedCacheSaveTask.cancel();
            generatedCacheSaveTask = null;
        }
    }

    private void trimGeneratedSkinCache() {
        long now = System.currentTimeMillis();
        generatedSkinCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        int excessEntries = generatedSkinCache.size() - maxGeneratedCacheEntries;
        if (excessEntries <= 0) {
            return;
        }
        generatedSkinCache.entrySet().stream()
            .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtMillis()))
            .limit(excessEntries)
            .map(Map.Entry::getKey)
            .forEach(generatedSkinCache::remove);
    }

    private boolean isLookupActive(UUID playerId, long lookupGeneration) {
        return Long.valueOf(lookupGeneration).equals(pendingLookups.get(playerId));
    }

    private void cancelScheduledLookup(UUID playerId) {
        BukkitRunnable scheduledLookup = scheduledLookups.remove(playerId);
        if (scheduledLookup != null) {
            scheduledLookup.cancel();
        }
    }

    private void cancelScheduledLookups() {
        for (UUID playerId : List.copyOf(scheduledLookups.keySet())) {
            cancelScheduledLookup(playerId);
        }
    }

    private void cancelRunningLookup(UUID playerId) {
        runningLookups.forEach((lookupKey, lookupTask) -> {
            if (lookupKey.playerId().equals(playerId) && runningLookups.remove(lookupKey, lookupTask)) {
                lookupTask.cancel(true);
            }
        });
        executor.purge();
    }

    private void cancelRunningLookups() {
        runningLookups.forEach((lookupKey, lookupTask) -> {
            if (runningLookups.remove(lookupKey, lookupTask)) {
                lookupTask.cancel(true);
            }
        });
        executor.purge();
    }

    private List<SkinProvider> loadProviders(FileConfiguration config) {
        ConfigurationSection providersSection = config.getConfigurationSection("skin-bridge.providers");
        if (providersSection == null) {
            return List.of();
        }

        List<SkinProvider> loadedProviders = new ArrayList<>();
        for (String key : providersSection.getKeys(false)) {
            if ("littleskin".equalsIgnoreCase(key)) {
                continue;
            }
            ConfigurationSection providerSection = providersSection.getConfigurationSection(key);
            if (providerSection == null || !providerSection.getBoolean("enabled", false)) {
                continue;
            }
            String profileUrl = providerSection.getString("profile-url", "").trim();
            if (!profileUrl.contains("{uuid}") && !profileUrl.contains("{uuid-dashed}")) {
                plugin.getLogger().warning("SkinBridge Provider " + key + " 缺少 {uuid} 或 {uuid-dashed} 占位符，已跳过。");
                continue;
            }
            try {
                URI profileUri = URI.create(profileUrl.replace("{uuid}", "00000000000000000000000000000000")
                    .replace("{uuid-dashed}", "00000000-0000-0000-0000-000000000000"));
                if (!"https".equalsIgnoreCase(profileUri.getScheme())) {
                    plugin.getLogger().warning("SkinBridge Provider " + key + " 必须使用 HTTPS，已跳过。");
                    continue;
                }
                String configuredName = providerSection.getString("name", key);
                String providerName = configuredName == null ? key : configuredName.trim();
                if (providerName.isEmpty()) {
                    providerName = key;
                }
                loadedProviders.add(new SkinProvider(key, providerName, profileUrl,
                    providerSection.getInt("priority", 100)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("SkinBridge Provider " + key + " 的 profile-url 无效，已跳过。");
            }
        }
        loadedProviders.sort(Comparator.comparingInt(SkinProvider::priority).thenComparing(SkinProvider::id));
        return List.copyOf(loadedProviders);
    }

    private SkinBridgeGateway loadGateway(String endpoint, String apiKey, String visibility, int timeoutSeconds,
                                          long minimumSubmitIntervalMillis) {
        if (apiKey.isBlank()) {
            return null;
        }
        try {
            return new MineSkinGateway(httpClient, endpoint, apiKey, visibility, timeoutSeconds,
                minimumSubmitIntervalMillis,
                "EssentialsC/" + plugin.getPluginMeta().getVersion() + " SkinBridge");
        } catch (Exception | LinkageError exception) {
            plugin.getLogger().warning("加载 MineSkin SkinBridge 适配器失败: " + exception.getMessage());
            return null;
        }
    }

    private Set<String> loadNormalizedValues(FileConfiguration config, String path) {
        Set<String> values = ConcurrentHashMap.newKeySet();
        for (String value : config.getStringList(path)) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    private boolean isExcluded(Player player) {
        return excludedUuids.contains(player.getUniqueId().toString().toLowerCase(java.util.Locale.ROOT))
            || excludedNames.contains(player.getName().toLowerCase(java.util.Locale.ROOT));
    }

    private void applySkin(UUID playerId, CachedLookup resolved, long lookupGeneration) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (lookupGeneration != configurationGeneration.get()) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId);
            SkinBridgeGateway currentGateway = gateway;
            if (player == null || !player.isOnline() || currentGateway == null || resolved.skin() == null) {
                return;
            }
            try {
                currentGateway.applySkin(player, resolved.skin());
                sendPlayerNotification(playerId, "skin-bridge.notifications.synced",
                    Map.of("provider", resolved.providerId()));
                if (debug) {
                    plugin.getLogger().info("SkinBridge 已应用 " + player.getName() + " 的 " + resolved.providerId() + " 皮肤。");
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("SkinBridge 应用 " + player.getName() + " 的皮肤失败: " + exception.getMessage());
                sendPlayerNotification(playerId, "skin-bridge.notifications.failed", Map.of());
            }
        });
    }

    private void sendPlayerNotification(UUID playerId, String messagePath, Map<String, String> placeholders) {
        if (!sendPlayerMessage) {
            return;
        }

        Runnable notification = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(EssentialsC.getLangManager().getPrefixedString(messagePath, placeholders));
            }
        };
        if (Bukkit.isPrimaryThread()) {
            notification.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, notification);
        }
    }

    private CachedLookup cached(String providerId, GeneratedSkin skin, State state) {
        return new CachedLookup(providerId, skin, state, System.currentTimeMillis() + Duration.ofMinutes(cacheMinutes).toMillis());
    }

    private static String requireString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalStateException("缺少字符串字段: " + key);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) {
            throw new IllegalStateException("字符串字段为空: " + key);
        }
        return value;
    }

    private static String normalizeUuid(String value) {
        return value.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record SkinProvider(String id, String name, String profileUrl, int priority) {
        private String resolveProfileUrl(UUID playerId) {
            return profileUrl.replace("{uuid}", playerId.toString().replace("-", ""))
                .replace("{uuid-dashed}", playerId.toString());
        }
    }

    private record ProviderProfile(String skinUrl, SkinModel model) {
    }

    private record LookupKey(UUID playerId, long generation) {
    }

    private record CachedLookup(String providerId, GeneratedSkin skin, State state, long expiresAtMillis) {
        private boolean hasExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    private static final class SkinBridgeThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EssentialsC-SkinBridge");
            thread.setDaemon(true);
            return thread;
        }
    }

    public enum SyncResult {
        QUEUED,
        CACHED,
        ALREADY_RUNNING,
        EXCLUDED,
        QUEUE_FULL,
        DEPENDENCY_MISSING,
        NO_PROVIDERS,
        REFRESH_COOLDOWN
    }

    public record Status(State state, String providerId) {
    }

    public enum State {
        EXTERNAL,
        EXCLUDED,
        NOT_EXTERNAL,
        PENDING,
        UNKNOWN
    }
}
