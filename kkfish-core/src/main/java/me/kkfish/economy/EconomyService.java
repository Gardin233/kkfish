package me.kkfish.economy;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;

import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import net.milkbowl.vault.economy.Economy;

import me.kkfish.kkfish;
import me.kkfish.misc.MessageManager;

/**
 * 统一经济服务门面，封装 Vault 和 PlayerPoints。
 *
 * <p>将 kkfish.java 中分散的 setupEconomy()、setupPlayerPoints() 逻辑收敛到此处，
 * 提供单一入口查询经济可用性、存款、取款、查询余额、点数操作。</p>
 */
public class EconomyService {

    private static final String VAULT_UNLOCKED_ECONOMY_CLASS = "net.milkbowl.vault2.economy.Economy";
    private static final String VAULT_UNLOCKED_RESPONSE_CLASS = "net.milkbowl.vault2.economy.EconomyResponse";

    public enum RewardType {
        VAULT,
        PLAYER_POINTS,
        NONE
    }

    public static class SellPay {
        private final int vaultAmount;
        private final int pointsAmount;

        public SellPay(int vaultAmount, int pointsAmount) {
            this.vaultAmount = Math.max(0, vaultAmount);
            this.pointsAmount = Math.max(0, pointsAmount);
        }

        public int getVaultAmount() {
            return vaultAmount;
        }

        public int getPointsAmount() {
            return pointsAmount;
        }

        public boolean hasAny() {
            return vaultAmount > 0 || pointsAmount > 0;
        }

        public boolean hasBoth() {
            return vaultAmount > 0 && pointsAmount > 0;
        }

        public int getTotalAmount() {
            return vaultAmount + pointsAmount;
        }

        public SellPay add(SellPay other) {
            if (other == null) return this;
            return new SellPay(vaultAmount + other.vaultAmount, pointsAmount + other.pointsAmount);
        }

        public SellPay multiply(int amount) {
            if (amount <= 0) return new SellPay(0, 0);
            return new SellPay(vaultAmount * amount, pointsAmount * amount);
        }
    }

    private final kkfish plugin;
    private Economy economy;
    private Object vaultUnlockedEconomy;
    private PlayerPointsAPI playerPointsAPI;
    private boolean currencyConfigNoticeLogged;

    public EconomyService(kkfish plugin) {
        this.plugin = plugin;
    }

    /**
     * 初始化 Vault 经济和 PlayerPoints 点数系统。
     */
    public void initialize() {
        setupEconomy();
        setupPlayerPoints();
    }

    private void setupEconomy() {
        MessageManager mm = plugin.getMessageManager();
        economy = findLegacyVaultEconomy();
        vaultUnlockedEconomy = findVaultUnlockedEconomy();

        if (hasVaultProvider()) {
            kkfish.log(mm.getMessageWithoutPrefix("log.economy_success",
                    "Successfully connected to economy system~"));
            logCurrencyConfiguration();
        } else {
            kkfish.log(mm.getMessageWithoutPrefix("log.no_economy",
                    "Vault or economy plugin not found! Economy features will be unavailable."));
        }
    }

    private Economy findLegacyVaultEconomy() {
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return rsp != null ? rsp.getProvider() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object findVaultUnlockedEconomy() {
        try {
            Class<?> economyClass = Class.forName(VAULT_UNLOCKED_ECONOMY_CLASS);
            RegisteredServiceProvider rsp = plugin.getServer().getServicesManager().getRegistration((Class) economyClass);
            return rsp != null ? rsp.getProvider() : null;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable t) {
            kkfish.log("§eVaultUnlocked economy lookup failed: " + t.getMessage());
            return null;
        }
    }

    private void logCurrencyConfiguration() {
        String configuredCurrency = getConfiguredVaultCurrency();
        if (configuredCurrency == null) {
            return;
        }

        if (vaultUnlockedEconomy == null) {
            kkfish.log("§eVaultUnlocked currency '" + configuredCurrency
                    + "' is configured, but VaultUnlockedAPI is not available. Falling back to default Vault behavior.");
            return;
        }

        if (!supportsVaultUnlockedMultiCurrency()) {
            kkfish.log("§eVaultUnlocked currency '" + configuredCurrency
                    + "' is configured, but the current economy provider does not support multiple currencies.");
            return;
        }

        if (!vaultUnlockedHasCurrency(configuredCurrency)) {
            String defaultCurrency = getVaultUnlockedDefaultCurrency();
            kkfish.log("§eVaultUnlocked currency '" + configuredCurrency
                    + "' was not found. Falling back to "
                    + (defaultCurrency != null ? ("default currency '" + defaultCurrency + "'") : "provider default currency") + ".");
            return;
        }

        kkfish.log("§aVaultUnlocked currency selected: " + configuredCurrency);
    }

    private void setupPlayerPoints() {
        MessageManager mm = plugin.getMessageManager();
        Plugin playerPointsPlugin = plugin.getServer().getPluginManager().getPlugin("PlayerPoints");
        if (playerPointsPlugin == null) {
            kkfish.log(mm.getMessageWithoutPrefix("log.player_points_not_found",
                    "PlayerPoints plugin not found, point purchase features will be unavailable."));
            playerPointsAPI = null;
            return;
        }

        try {
            Method getAPIMethod = playerPointsPlugin.getClass().getMethod("getAPI");
            if (Modifier.isStatic(getAPIMethod.getModifiers())) {
                playerPointsAPI = (PlayerPointsAPI) getAPIMethod.invoke(null);
            } else {
                playerPointsAPI = (PlayerPointsAPI) getAPIMethod.invoke(playerPointsPlugin);
            }
            kkfish.log(mm.getMessageWithoutPrefix("log.player_points_success",
                    "Successfully connected to PlayerPoints system~"));
        } catch (Exception e) {
            kkfish.log("§e=== PlayerPoints 诊断信息 ===");
            kkfish.log("§e插件类名: " + playerPointsPlugin.getClass().getName());
            kkfish.log("§e插件类加载器: " + playerPointsPlugin.getClass().getClassLoader());

            // 检查 getAPI 方法是否存在
            Method[] methods = playerPointsPlugin.getClass().getMethods();
            boolean hasGetAPI = false;
            for (Method m : methods) {
                if ("getAPI".equals(m.getName())) {
                    hasGetAPI = true;
                    kkfish.log("§e找到 getAPI 方法，参数类型: "
                            + Arrays.toString(m.getParameterTypes())
                            + "，返回类型: " + m.getReturnType().getName()
                            + "，是否静态: " + Modifier.isStatic(m.getModifiers()));
                }
            }
            if (!hasGetAPI) {
                kkfish.log("§e未找到 getAPI 方法！可用公共方法:");
                for (Method m : methods) {
                    if (m.getDeclaringClass() != Object.class) {
                        kkfish.log("§e  - " + m.getName()
                                + "(" + Arrays.toString(m.getParameterTypes()) + ")");
                    }
                }
            }

            kkfish.log("§e异常类型: " + e.getClass().getName());
            kkfish.log("§e异常信息: " + e.getMessage());
            kkfish.log("§e=== 尝试通过 ServicesManager 获取 ===");

            // 备用方案：通过 Bukkit ServicesManager 获取
            try {
                ServicesManager sm = plugin.getServer().getServicesManager();
                RegisteredServiceProvider<PlayerPointsAPI> rsp =
                        sm.getRegistration(PlayerPointsAPI.class);
                if (rsp != null) {
                    playerPointsAPI = rsp.getProvider();
                    kkfish.log(mm.getMessageWithoutPrefix("log.player_points_success",
                            "Successfully connected to PlayerPoints system (via ServicesManager)~"));
                } else {
                    kkfish.log("§eServicesManager 中未注册 PlayerPointsAPI 服务");
                    playerPointsAPI = null;
                }
            } catch (Exception fallbackEx) {
                kkfish.log("§eServicesManager 备用方案也失败: "
                        + fallbackEx.getClass().getName() + " - " + fallbackEx.getMessage());
                playerPointsAPI = null;
            }

            if (playerPointsAPI == null) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 按配置和插件可用性选择出售奖励落点。
     *
     * @param economyEnabled 经济总开关
     * @param vaultEnabled   Vault 开关
     * @param vaultReady     Vault 服务是否已连接
     * @param pointsReady    PlayerPoints API 是否已连接
     * @return 本次应该使用的经济类型
     */
    public static RewardType chooseRewardType(boolean economyEnabled, boolean vaultEnabled, boolean vaultReady, boolean pointsReady) {
        if (!economyEnabled) {
            return RewardType.NONE;
        }
        if (vaultEnabled && vaultReady) {
            return RewardType.VAULT;
        }
        if (pointsReady) {
            return RewardType.PLAYER_POINTS;
        }
        return RewardType.NONE;
    }

    public static RewardType chooseRewardType(boolean economyEnabled, boolean vaultEnabled, boolean pointsEnabled,
                                              boolean vaultReady, boolean pointsReady, String primary, boolean fallback) {
        if (!economyEnabled) {
            return RewardType.NONE;
        }

        boolean canVault = vaultEnabled && vaultReady;
        boolean canPoints = pointsEnabled && pointsReady;
        boolean primaryPoints = isPlayerPointsName(primary);

        if (primaryPoints) {
            if (canPoints) return RewardType.PLAYER_POINTS;
            if (fallback && canVault) return RewardType.VAULT;
            return RewardType.NONE;
        }

        if (canVault) return RewardType.VAULT;
        if (fallback && canPoints) return RewardType.PLAYER_POINTS;
        return RewardType.NONE;
    }

    public static SellPay resolveSellPay(SellValue value, String primary, boolean fallback, boolean economyEnabled,
                                         boolean vaultEnabled, boolean pointsEnabled, boolean vaultReady, boolean pointsReady) {
        if (value == null || !economyEnabled) {
            return new SellPay(0, 0);
        }

        boolean canVault = vaultEnabled && vaultReady;
        boolean canPoints = pointsEnabled && pointsReady;

        if (value.hasSplitValue()) {
            int vaultAmount = value.getVaultValue() > 0 && canVault ? value.getVaultValue() : 0;
            int pointsAmount = value.getPointsValue() > 0 && canPoints ? value.getPointsValue() : 0;
            return new SellPay(vaultAmount, pointsAmount);
        }

        int oldValue = value.getOldValue();
        if (oldValue <= 0) {
            return new SellPay(0, 0);
        }

        boolean primaryPoints = isPlayerPointsName(primary);

        if (primaryPoints) {
            if (canPoints) return new SellPay(0, oldValue);
            if (fallback && canVault) return new SellPay(oldValue, 0);
            return new SellPay(0, 0);
        }

        if (canVault) return new SellPay(oldValue, 0);
        if (fallback && canPoints) return new SellPay(0, oldValue);
        return new SellPay(0, 0);
    }

    public RewardType getRewardType() {
        return chooseRewardType(
                plugin.getCustomConfig().isEconomyEnabled(),
                plugin.getCustomConfig().isEconomySystemEnabled(),
                plugin.getCustomConfig().isPlayerPointsEconomyEnabled(),
                hasVaultProvider(),
                playerPointsAPI != null,
                plugin.getCustomConfig().getPrimaryEconomy(),
                plugin.getCustomConfig().isEconomyFallbackEnabled());
    }

    /**
     * @return Vault 或 PlayerPoints 其中之一是否可用
     */
    public boolean isEconomyEnabled() {
        if (!plugin.getCustomConfig().isEconomyEnabled()) {
            return false;
        }
        return (plugin.getCustomConfig().isEconomySystemEnabled() && hasVaultProvider())
                || (plugin.getCustomConfig().isPlayerPointsEconomyEnabled() && playerPointsAPI != null);
    }

    /**
     * @return PlayerPoints 是否可用
     */
    public boolean isPlayerPointsEnabled() {
        return playerPointsAPI != null;
    }

    public Economy getEconomy() {
        return economy;
    }

    public boolean isVaultReady() {
        return plugin.getCustomConfig().isEconomySystemEnabled() && hasVaultProvider();
    }

    public String getEffectiveVaultCurrency() {
        return resolveVaultUnlockedCurrency();
    }

    public PlayerPointsAPI getPlayerPointsAPI() {
        return playerPointsAPI;
    }

    public SellPay resolveSellPay(SellValue value) {
        return resolveSellPay(
                value,
                plugin.getCustomConfig().getPrimaryEconomy(),
                plugin.getCustomConfig().isEconomyFallbackEnabled(),
                plugin.getCustomConfig().isEconomyEnabled(),
                plugin.getCustomConfig().isEconomySystemEnabled(),
                plugin.getCustomConfig().isPlayerPointsEconomyEnabled(),
                hasVaultProvider(),
                playerPointsAPI != null);
    }

    public boolean depositSellPay(OfflinePlayer player, SellPay pay) {
        if (player == null || pay == null || !pay.hasAny()) return false;

        boolean success = true;
        if (pay.getVaultAmount() > 0) {
            success = depositVault(player, pay.getVaultAmount()) && success;
        }

        if (pay.getPointsAmount() > 0) {
            success = givePoints(player.getUniqueId(), pay.getPointsAmount()) && success;
        }

        return success;
    }

    /**
     * 给玩家发放经济奖励，Vault 可用时优先走 Vault，否则落到 PlayerPoints。
     *
     * @param player 目标玩家
     * @param amount 金额
     * @return 是否成功
     */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0) return false;
        RewardType rewardType = getRewardType();
        if (rewardType == RewardType.VAULT) {
            return depositVault(player, amount);
        }
        if (rewardType == RewardType.PLAYER_POINTS) {
            return givePoints(player.getUniqueId(), amountToPoints(amount));
        }
        return false;
    }

    /**
     * 从玩家取款。
     *
     * @param player 目标玩家
     * @param amount 金额
     * @return 是否成功
     */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0) return false;
        RewardType rewardType = getRewardType();
        if (rewardType == RewardType.VAULT) {
            return withdrawVault(player, amount);
        }
        if (rewardType == RewardType.PLAYER_POINTS) {
            return takePoints(player.getUniqueId(), amountToPoints(amount));
        }
        return false;
    }

    /**
     * 查询玩家余额。
     *
     * @param player 目标玩家
     * @return 余额，经济不可用时返回 0
     */
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0;
        RewardType rewardType = getRewardType();
        if (rewardType == RewardType.VAULT) {
            return getVaultBalance(player);
        }
        if (rewardType == RewardType.PLAYER_POINTS) {
            return getPoints(player.getUniqueId());
        }
        return 0;
    }

    public double getVaultBalance(OfflinePlayer player) {
        if (player == null || !isVaultReady()) return 0;

        if (vaultUnlockedEconomy != null) {
            Object result = hasConfiguredVaultCurrency()
                    ? invokeVaultUnlocked("balance",
                            new Class<?>[]{String.class, UUID.class, String.class, String.class},
                            plugin.getName(), player.getUniqueId(), resolveWorldName(player), resolveVaultUnlockedCurrency())
                    : invokeVaultUnlocked("balance",
                            new Class<?>[]{String.class, UUID.class},
                            plugin.getName(), player.getUniqueId());
            if (result instanceof BigDecimal) {
                return ((BigDecimal) result).doubleValue();
            }
        }

        return economy != null ? economy.getBalance(player) : 0;
    }

    public boolean depositVault(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0 || !isVaultReady()) return false;

        if (vaultUnlockedEconomy != null) {
            Object response = hasConfiguredVaultCurrency()
                    ? invokeVaultUnlocked("deposit",
                            new Class<?>[]{String.class, UUID.class, String.class, String.class, BigDecimal.class},
                            plugin.getName(), player.getUniqueId(), resolveWorldName(player), resolveVaultUnlockedCurrency(), toBigDecimal(amount))
                    : invokeVaultUnlocked("deposit",
                            new Class<?>[]{String.class, UUID.class, BigDecimal.class},
                            plugin.getName(), player.getUniqueId(), toBigDecimal(amount));
            return isSuccessfulVaultUnlockedResponse(response);
        }

        return economy != null && economy.depositPlayer(player, amount).transactionSuccess();
    }

    public boolean withdrawVault(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0 || !isVaultReady()) return false;

        if (vaultUnlockedEconomy != null) {
            Object response = hasConfiguredVaultCurrency()
                    ? invokeVaultUnlocked("withdraw",
                            new Class<?>[]{String.class, UUID.class, String.class, String.class, BigDecimal.class},
                            plugin.getName(), player.getUniqueId(), resolveWorldName(player), resolveVaultUnlockedCurrency(), toBigDecimal(amount))
                    : invokeVaultUnlocked("withdraw",
                            new Class<?>[]{String.class, UUID.class, BigDecimal.class},
                            plugin.getName(), player.getUniqueId(), toBigDecimal(amount));
            return isSuccessfulVaultUnlockedResponse(response);
        }

        return economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    /**
     * 给玩家加点数。
     *
     * @param playerId 玩家 UUID
     * @param amount   点数
     * @return 是否成功
     */
    public boolean givePoints(UUID playerId, int amount) {
        if (playerPointsAPI == null) return false;
        return playerPointsAPI.give(playerId, amount);
    }

    public boolean takePoints(UUID playerId, int amount) {
        if (playerPointsAPI == null) return false;
        return playerPointsAPI.take(playerId, amount);
    }

    /**
     * 查询玩家点数。
     *
     * @param playerId 玩家 UUID
     * @return 点数，不可用时返回 0
     */
    public int getPoints(UUID playerId) {
        if (playerPointsAPI == null) return 0;
        return playerPointsAPI.look(playerId);
    }

    private int amountToPoints(double amount) {
        return (int) Math.max(1, Math.round(amount));
    }

    private boolean hasVaultProvider() {
        return economy != null || vaultUnlockedEconomy != null;
    }

    private String getConfiguredVaultCurrency() {
        String configured = plugin.getCustomConfig().getVaultCurrency();
        if (configured == null) return null;
        configured = configured.trim();
        if (configured.isEmpty()
                || "default".equalsIgnoreCase(configured)
                || "auto".equalsIgnoreCase(configured)) {
            return null;
        }
        return configured;
    }

    private boolean hasConfiguredVaultCurrency() {
        return resolveVaultUnlockedCurrency() != null;
    }

    private String resolveVaultUnlockedCurrency() {
        String configuredCurrency = getConfiguredVaultCurrency();
        if (configuredCurrency == null || vaultUnlockedEconomy == null) {
            return null;
        }

        if (!supportsVaultUnlockedMultiCurrency()) {
            logCurrencyConfigIgnoredOnce("configured, but the economy provider does not support multiple currencies");
            return null;
        }

        if (vaultUnlockedHasCurrency(configuredCurrency)) {
            return configuredCurrency;
        }

        logCurrencyConfigIgnoredOnce("configured, but the currency does not exist in the current provider");
        return null;
    }

    private void logCurrencyConfigIgnoredOnce(String reason) {
        if (currencyConfigNoticeLogged) {
            return;
        }
        String configuredCurrency = getConfiguredVaultCurrency();
        if (configuredCurrency != null) {
            kkfish.log("§eVaultUnlocked currency '" + configuredCurrency + "' is " + reason + ". Using provider default currency instead.");
            currencyConfigNoticeLogged = true;
        }
    }

    private boolean supportsVaultUnlockedMultiCurrency() {
        Object result = invokeVaultUnlocked("hasMultiCurrencySupport", new Class<?>[0]);
        return result instanceof Boolean && (Boolean) result;
    }

    private boolean vaultUnlockedHasCurrency(String currency) {
        Object result = invokeVaultUnlocked("hasCurrency", new Class<?>[]{String.class}, currency);
        return result instanceof Boolean && (Boolean) result;
    }

    private String getVaultUnlockedDefaultCurrency() {
        Object result = invokeVaultUnlocked("getDefaultCurrency", new Class<?>[]{String.class}, plugin.getName());
        return result instanceof String ? (String) result : null;
    }

    private Object invokeVaultUnlocked(String methodName, Class<?>[] parameterTypes, Object... args) {
        if (vaultUnlockedEconomy == null) {
            return null;
        }
        try {
            Method method = vaultUnlockedEconomy.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(vaultUnlockedEconomy, args);
        } catch (Throwable t) {
            kkfish.log("§eVaultUnlocked call failed: " + methodName + " - " + t.getMessage());
            return null;
        }
    }

    private boolean isSuccessfulVaultUnlockedResponse(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            Object result = successMethod.invoke(response);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            try {
                if (!VAULT_UNLOCKED_RESPONSE_CLASS.equals(response.getClass().getName())) {
                    return false;
                }
                Field typeField = response.getClass().getField("type");
                Object type = typeField.get(response);
                return type != null && "SUCCESS".equals(String.valueOf(type));
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private BigDecimal toBigDecimal(double amount) {
        return BigDecimal.valueOf(amount);
    }

    private String resolveWorldName(OfflinePlayer player) {
        if (player instanceof Player) {
            return ((Player) player).getWorld().getName();
        }
        if (player.isOnline() && player.getPlayer() != null) {
            return player.getPlayer().getWorld().getName();
        }
        return plugin.getServer().getWorlds().isEmpty() ? "world" : plugin.getServer().getWorlds().get(0).getName();
    }

    private static boolean isPlayerPointsName(String name) {
        return "playerpoints".equalsIgnoreCase(name)
                || "points".equalsIgnoreCase(name)
                || "pp".equalsIgnoreCase(name);
    }
}
