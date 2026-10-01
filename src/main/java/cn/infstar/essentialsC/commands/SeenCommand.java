package cn.infstar.essentialsC.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class SeenCommand extends BaseCommand {

    public SeenCommand() {
        super("essentialsc.command.seen");
    }

    @Override
    protected boolean execute(Player player, String[] args) {
        return executeCommand(player, player, args);
    }

    @Override
    protected boolean executeConsole(CommandSender sender, String[] args) {
        return executeCommand(sender, null, args);
    }

    private boolean executeCommand(CommandSender sender, Player viewer, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(getLang().getPrefixedString(viewer == null
                ? "messages.seen-usage-console"
                : "messages.seen-usage"));
            return true;
        }

        Player onlineTarget = Bukkit.getPlayerExact(args[0]);
        if (onlineTarget != null && VanishCommand.isVanished(onlineTarget)
            && viewer != null && !viewer.hasPermission(VanishCommand.SEE_PERMISSION)) {
            sender.sendMessage(getLang().getPrefixedString("messages.player-not-found", Map.of("player", args[0])));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(getLang().getPrefixedString("messages.player-not-found", Map.of("player", args[0])));
            return true;
        }

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sender.sendMessage(getLang().getPrefixedComponent("messages.seen-header",
            Map.of("player", String.valueOf(target.getName()))));
        sender.sendMessage(getLang().getComponent("messages.seen-uuid",
            Map.of("uuid", target.getUniqueId().toString())));
        sender.sendMessage(getLang().getComponent("messages.seen-operator",
            Map.of("value", getLang().getString(target.isOp() ? "messages.seen-yes" : "messages.seen-no"))));
        sender.sendMessage(getLang().getComponent("messages.seen-whitelisted",
            Map.of("value", getLang().getString(target.isWhitelisted() ? "messages.seen-yes" : "messages.seen-no"))));
        sender.sendMessage(getLang().getComponent("messages.seen-banned",
            Map.of("value", getLang().getString(target.isBanned() ? "messages.seen-yes" : "messages.seen-no"))));
        if (target.getLastLogin() > 0) {
            sender.sendMessage(getLang().getComponent("messages.seen-last-login",
                Map.of("time", format.format(new Date(target.getLastLogin())))));
        }

        if (target.isOnline()) {
            sender.sendMessage(getLang().getComponent("messages.seen-status-online"));
            Player onlinePlayer = target.getPlayer();
            if (onlinePlayer != null) {
                sendOnlineDetails(sender, onlinePlayer);
            }
        } else {
            sender.sendMessage(getLang().getComponent("messages.seen-status-offline"));
            long lastSeen = target.getLastSeen();
            if (lastSeen > 0) {
                sender.sendMessage(getLang().getComponent("messages.seen-last-online",
                    Map.of("time", format.format(new Date(lastSeen)))));
            }
        }

        sender.sendMessage(getLang().getComponent("messages.seen-first-joined",
            Map.of("time", format.format(new Date(target.getFirstPlayed())))));
        return true;
    }

    private void sendOnlineDetails(CommandSender sender, Player player) {
        var location = player.getLocation();
        String address = player.getAddress() == null || player.getAddress().getAddress() == null
            ? getLang().getString("messages.seen-unknown")
            : player.getAddress().getAddress().getHostAddress();
        String clientBrand = player.getClientBrandName();
        if (clientBrand == null || clientBrand.isBlank()) {
            clientBrand = getLang().getString("messages.seen-unknown");
        }
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? player.getHealth() : maxHealthAttribute.getValue();

        sender.sendMessage(getLang().getComponent("messages.seen-world",
            Map.of("world", player.getWorld().getName())));
        sender.sendMessage(getLang().getComponent("messages.seen-location", Map.of(
            "x", formatNumber(location.getX()),
            "y", formatNumber(location.getY()),
            "z", formatNumber(location.getZ())
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-gamemode", Map.of(
            "mode", getLang().getString("messages.seen-gamemode-values."
                + player.getGameMode().name().toLowerCase(Locale.ROOT))
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-health", Map.of(
            "health", formatNumber(player.getHealth()),
            "max_health", formatNumber(maxHealth)
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-food", Map.of(
            "food", Integer.toString(player.getFoodLevel())
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-level", Map.of(
            "level", Integer.toString(player.getLevel()),
            "experience", formatNumber(player.getExp() * 100.0D)
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-ping", Map.of(
            "ping", Integer.toString(Math.max(0, player.getPing()))
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-locale", Map.of(
            "locale", player.locale().toLanguageTag()
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-client", Map.of(
            "client", clientBrand
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-ip", Map.of("address", address)));
        sender.sendMessage(getLang().getComponent("messages.seen-flying", Map.of(
            "value", getLang().getString(player.isFlying() ? "messages.seen-yes" : "messages.seen-no")
        )));
        sender.sendMessage(getLang().getComponent("messages.seen-playtime", Map.of(
            "time", formatDuration(player.getStatistic(Statistic.PLAY_ONE_MINUTE))
        )));
    }

    private String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String formatDuration(int ticks) {
        long[] duration = durationParts(ticks);
        return getLang().getString("messages.seen-duration", Map.of(
            "days", Long.toString(duration[0]),
            "hours", Long.toString(duration[1]),
            "minutes", Long.toString(duration[2])
        ));
    }

    static long[] durationParts(int ticks) {
        long minutes = Math.max(0L, ticks) / (20L * 60L);
        return new long[]{minutes / (24L * 60L), minutes % (24L * 60L) / 60L, minutes % 60L};
    }
}
