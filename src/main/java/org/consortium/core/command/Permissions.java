package org.consortium.core.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.consortium.core.ConsortiumCore;

import java.util.List;
import java.util.function.Predicate;

/**
 * NeoForge {@code PermissionAPI} nodes (LuckPerms resolves them). Defaults: player nodes true, admin nodes op level 2,
 * identity op level 4. The console has no player, so it falls back to the vanilla permission level.
 */
public final class Permissions {
    public static final PermissionNode<Boolean> CREDITS_BALANCE = node("credits.balance", 0, "See your own balance");
    public static final PermissionNode<Boolean> CREDITS_TOP = node("credits.top", 0, "See the public leaderboard");
    public static final PermissionNode<Boolean> PRICES_VIEW = node("prices.view", 0, "See the price table");
    public static final PermissionNode<Boolean> ADMIN_CREDITS = node("admin.credits", 2, "Add, take, set and inspect balances");
    public static final PermissionNode<Boolean> ADMIN_PRICES = node("admin.prices", 2, "Hot-adjust and reset prices");
    public static final PermissionNode<Boolean> ADMIN_LEDGER = node("admin.ledger", 2, "Read the ledger tail");
    public static final PermissionNode<Boolean> ADMIN_REPORT = node("admin.report", 2, "Print the money-supply report");
    public static final PermissionNode<Boolean> ADMIN_IDENTITY = node("admin.identity", 4, "Forget or purge connection hashes");
    /** v0.2 shop nodes (5.3): buying from the screen, the staff listing and opening, and buying on a player's behalf. */
    public static final PermissionNode<Boolean> SHOP_BUY = node("shop.buy", 0, "Open the shop from a terminal and buy");
    public static final PermissionNode<Boolean> ADMIN_SHOP = node("admin.shop", 2, "List the shop catalogue and open it for a player");
    public static final PermissionNode<Boolean> ADMIN_SHOP_BUY = node("admin.shop_buy", 4, "Buy a shop entry with another player's credits");

    private static final List<PermissionNode<?>> ALL = List.of(CREDITS_BALANCE, CREDITS_TOP, PRICES_VIEW,
            ADMIN_CREDITS, ADMIN_PRICES, ADMIN_LEDGER, ADMIN_REPORT, ADMIN_IDENTITY, SHOP_BUY, ADMIN_SHOP, ADMIN_SHOP_BUY);

    private Permissions() {
    }

    private static PermissionNode<Boolean> node(String name, int defaultLevel, String description) {
        PermissionNode<Boolean> node = new PermissionNode<>(ConsortiumCore.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null ? player.hasPermissions(defaultLevel) : defaultLevel == 0);
        node.setInformation(Component.literal("consortium." + name), Component.literal(description));
        return node;
    }

    public static void gather(PermissionGatherEvent.Nodes event) {
        event.addNodes(ALL);
    }

    /** The same check as {@link #require} for a player outside a command (payload handlers). */
    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node, int level) {
        if (player == null) {
            return false;
        }
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (RuntimeException e) {
            return player.hasPermissions(level);
        }
    }

    /**
     * Command predicate: a player is checked through the permission API (LuckPerms), the console through the vanilla
     * level. The same predicate runs when the server builds each player's command tree.
     */
    public static Predicate<CommandSourceStack> require(PermissionNode<Boolean> node, int level) {
        return src -> {
            ServerPlayer player = src.getPlayer();
            if (player == null) {
                return src.hasPermission(level);
            }
            try {
                return PermissionAPI.getPermission(player, node);
            } catch (RuntimeException e) {
                // Permission handler not initialised yet (should not happen after server start): vanilla fallback.
                return player.hasPermissions(level);
            }
        };
    }
}
