package org.consortium.core.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.pricing.FamilyIndex;
import org.consortium.core.pricing.PriceFamily;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Shared pieces of the three command trees: argument resolution, suggestions, messages. */
final class CommandSupport {
    static final SimpleCommandExceptionType NOT_READY = new SimpleCommandExceptionType(Component.literal("The Consortium economy is not ready (server starting or ledger unavailable)."));
    static final SimpleCommandExceptionType NO_PLAYER = new SimpleCommandExceptionType(Component.literal("This command needs a player."));

    /** A resolved {@code <key>}: the family and, when the key was an item id, the item and its weight. */
    record KeyTarget(PriceFamily family, Item item, double weight) {
    }

    /** A resolved {@code <player>} argument: uuid and name, from the account list or a game profile. */
    record Target(java.util.UUID uuid, String name) {
    }

    private CommandSupport() {
    }

    static ConsortiumRuntime runtime() throws CommandSyntaxException {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null) {
            throw NOT_READY.create();
        }
        return rt;
    }

    static ServerPlayer player(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            throw NO_PLAYER.create();
        }
        return p;
    }

    /** {@code admin:<uuid>} for a player, {@code console} otherwise. */
    static String counterpart(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? "console" : "admin:" + p.getUUID();
    }

    static String actor(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? "console" : p.getGameProfile().getName();
    }

    /** Suggests family keys and indexed item ids. */
    static final SuggestionProvider<CommandSourceStack> KEY_SUGGESTIONS = (ctx, builder) -> {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null) {
            return builder.buildFuture();
        }
        List<ResourceLocation> ids = new ArrayList<>();
        for (PriceFamily f : rt.prices.families()) {
            ResourceLocation rl = ResourceLocation.tryParse(f.key());
            if (rl != null) {
                ids.add(rl);
            }
        }
        for (String id : rt.prices.index().itemIds()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && !ids.contains(rl)) {
                ids.add(rl);
            }
        }
        return SharedSuggestionProvider.suggestResource(ids, builder);
    };

    /** Suggests every item id for {@code /prices set} on items outside the table. */
    static final SuggestionProvider<CommandSourceStack> KEY_OR_ITEM_SUGGESTIONS = (ctx, builder) -> {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        Stream<ResourceLocation> families = rt == null ? Stream.empty()
                : rt.prices.families().stream().map(f -> ResourceLocation.tryParse(f.key())).filter(java.util.Objects::nonNull);
        return SharedSuggestionProvider.suggestResource(Stream.concat(families, BuiltInRegistries.ITEM.keySet().stream()), builder);
    };

    /** Suggests account names (offline players included). */
    static final SuggestionProvider<CommandSourceStack> ACCOUNT_SUGGESTIONS = (ctx, builder) -> {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null) {
            return builder.buildFuture();
        }
        List<String> names = new ArrayList<>();
        for (Account a : rt.economy.accounts()) {
            if (a.name != null && !a.name.startsWith("<")) {
                names.add(a.name);
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /** Resolves {@code <key>} as a family key, else as an indexed item id. Empty when neither. */
    static Optional<KeyTarget> resolveKey(ConsortiumRuntime rt, ResourceLocation id) {
        PriceFamily family = rt.prices.family(id.toString());
        if (family != null) {
            return Optional.of(new KeyTarget(family, null, 1));
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isPresent()) {
            Optional<FamilyIndex.Entry> entry = rt.prices.entryOf(item.get());
            if (entry.isPresent()) {
                PriceFamily f = rt.prices.family(entry.get().family());
                if (f != null) {
                    return Optional.of(new KeyTarget(f, item.get(), entry.get().weight()));
                }
            }
        }
        return Optional.empty();
    }

    static ResourceLocation key(CommandContext<CommandSourceStack> ctx) {
        return ResourceLocationArgument.getId(ctx, "key");
    }

    /**
     * Resolves the {@code <player>} game-profile argument to exactly one target. Offline names go through the
     * profile cache; an account name known to the ledger is accepted even when Mojang cannot resolve it.
     */
    static Target target(CommandContext<CommandSourceStack> ctx, ConsortiumRuntime rt) throws CommandSyntaxException {
        Collection<GameProfile> profiles;
        try {
            profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        } catch (CommandSyntaxException e) {
            // Unknown to Mojang or the cache: fall back to the ledger's account names.
            String raw = rawPlayerArgument(ctx);
            Account a = raw == null ? null : rt.economy.accountByName(raw);
            if (a == null) {
                throw e;
            }
            return new Target(a.uuid, a.name);
        }
        if (profiles.size() != 1) {
            throw new SimpleCommandExceptionType(Component.literal("Exactly one player is expected.")).create();
        }
        GameProfile profile = profiles.iterator().next();
        return new Target(profile.getId(), profile.getName());
    }

    private static String rawPlayerArgument(CommandContext<CommandSourceStack> ctx) {
        try {
            String input = ctx.getInput();
            var node = ctx.getNodes().stream().filter(n -> n.getNode().getName().equals("player")).findFirst();
            return node.map(parsed -> input.substring(parsed.getRange().getStart(), parsed.getRange().getEnd())).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static long amount(String text) throws CommandSyntaxException {
        try {
            long cents = Money.parseCredits(text);
            if (cents <= 0) {
                throw new SimpleCommandExceptionType(Component.literal("The amount must be above 0.")).create();
            }
            return cents;
        } catch (IllegalArgumentException e) {
            throw new SimpleCommandExceptionType(Component.literal(e.getMessage())).create();
        }
    }

    static Component ok(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    static Component info(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    static Component grey(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    static Component fail(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }
}
