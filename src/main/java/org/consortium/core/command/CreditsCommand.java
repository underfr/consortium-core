package org.consortium.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.Result;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.LedgerType;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Transactions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.consortium.core.command.CommandSupport.amount;
import static org.consortium.core.command.CommandSupport.counterpart;
import static org.consortium.core.command.CommandSupport.fail;
import static org.consortium.core.command.CommandSupport.info;
import static org.consortium.core.command.CommandSupport.ok;
import static org.consortium.core.command.CommandSupport.runtime;
import static org.consortium.core.command.CommandSupport.target;

/** {@code /credits} (specification 4): balance, leaderboard, and the admin money commands. */
public final class CreditsCommand {
    private CreditsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("credits")
                .requires(Permissions.require(Permissions.CREDITS_BALANCE, 0))
                .executes(CreditsCommand::balance)
                .then(Commands.literal("top")
                        .requires(Permissions.require(Permissions.CREDITS_TOP, 0))
                        .executes(ctx -> top(ctx, 10))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 25))
                                .executes(ctx -> top(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("balance")
                        .requires(Permissions.require(Permissions.ADMIN_CREDITS, 2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .suggests(CommandSupport.ACCOUNT_SUGGESTIONS)
                                .executes(CreditsCommand::balanceOf)))
                .then(money("add", LedgerType.ADMIN_ADD))
                .then(money("take", LedgerType.ADMIN_TAKE))
                .then(money("set", LedgerType.ADMIN_SET))
                .then(Commands.literal("grant-start")
                        .requires(Permissions.require(Permissions.ADMIN_CREDITS, 2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .suggests(CommandSupport.ACCOUNT_SUGGESTIONS)
                                .executes(CreditsCommand::grantStart))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> money(String name, LedgerType type) {
        return Commands.literal(name)
                .requires(Permissions.require(Permissions.ADMIN_CREDITS, 2))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .suggests(CommandSupport.ACCOUNT_SUGGESTIONS)
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> adminMoney(ctx, type)))));
    }

    private static int balance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        ServerPlayer player = CommandSupport.player(ctx.getSource());
        Account a = rt.economy.account(player.getUUID());
        long cents = a == null ? 0 : a.balance;
        ctx.getSource().sendSuccess(() -> info("Balance: " + Money.format(cents, CommonConfig.currencySymbol())), false);
        return 1;
    }

    private static int top(CommandContext<CommandSourceStack> ctx, int n) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        List<Account> accounts = new ArrayList<>(rt.economy.accounts());
        accounts.sort((a, b) -> Long.compare(b.rankCredit, a.rankCredit));
        ctx.getSource().sendSuccess(() -> info("Top " + Math.min(n, accounts.size()) + " by credits earned:"), false);
        int rank = 1;
        for (Account a : accounts) {
            if (rank > n) {
                break;
            }
            final String line = rank + ". " + a.name + " - " + Money.format(a.rankCredit, CommonConfig.currencySymbol()) + " earned";
            ctx.getSource().sendSuccess(() -> info(line), false);
            rank++;
        }
        if (accounts.isEmpty()) {
            ctx.getSource().sendSuccess(() -> CommandSupport.grey("No account yet."), false);
        }
        return 1;
    }

    private static int balanceOf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        CommandSupport.Target t = target(ctx, rt);
        Account a = rt.economy.account(t.uuid());
        if (a == null) {
            ctx.getSource().sendSuccess(() -> info(t.name() + " has no account yet."), false);
            return 1;
        }
        String first = a.firstLogin == 0 ? "never" : LocalDate.ofInstant(Instant.ofEpochMilli(a.firstLogin), ZoneOffset.UTC).toString();
        String symbol = CommonConfig.currencySymbol();
        ctx.getSource().sendSuccess(() -> info(a.name + ": " + Money.format(a.balance, symbol) + " (earned " + Money.format(a.rankCredit, symbol)
                + ", delivered " + Money.format(a.lifetimeDelivered, symbol) + ", " + org.consortium.core.economy.Units.format(a.lifetimeUnits)
                + " units, grant " + a.grant + ", first login " + first + ")"), false);
        return 1;
    }

    private static int adminMoney(CommandContext<CommandSourceStack> ctx, LedgerType type) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        CommandSupport.Target t = target(ctx, rt);
        String amountText = StringArgumentType.getString(ctx, "amount");
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        if (reason.isEmpty()) {
            ctx.getSource().sendFailure(fail("A reason is mandatory."));
            return 0;
        }
        long cents;
        if (type == LedgerType.ADMIN_SET) {
            try {
                cents = Money.parseCredits(amountText);
            } catch (IllegalArgumentException e) {
                ctx.getSource().sendFailure(fail(e.getMessage()));
                return 0;
            }
        } else {
            cents = amount(amountText);
        }
        Account before = rt.economy.account(t.uuid());
        long oldBalance = before == null ? 0 : before.balance;
        Transactions.Outcome outcome = switch (type) {
            case ADMIN_ADD -> rt.transactions.adminAdd(t.uuid(), t.name(), cents, reason, counterpart(ctx.getSource()));
            case ADMIN_TAKE -> rt.transactions.adminTake(t.uuid(), cents, reason, counterpart(ctx.getSource()));
            default -> rt.transactions.adminSet(t.uuid(), t.name(), cents, reason, counterpart(ctx.getSource()));
        };
        String symbol = CommonConfig.currencySymbol();
        switch (outcome.result()) {
            case SUCCESS -> {
                String verb = switch (type) {
                    case ADMIN_ADD -> "add";
                    case ADMIN_TAKE -> "take";
                    default -> "set";
                };
                ctx.getSource().sendSuccess(() -> ok(t.name() + ": " + Money.formatPlain(oldBalance) + " -> " + Money.format(outcome.balanceAfter(), symbol)
                        + " (" + verb + " " + Money.formatPlain(cents) + ": " + reason + ")"), true);
                return 1;
            }
            case INSUFFICIENT_FUNDS -> ctx.getSource().sendFailure(fail(t.name() + " only has " + Money.format(outcome.balanceAfter(), symbol) + "."));
            case UNKNOWN_PLAYER -> ctx.getSource().sendFailure(fail(t.name() + " has no account."));
            case VETOED -> ctx.getSource().sendFailure(fail("The change was vetoed by a listener."));
            case INVALID_AMOUNT -> ctx.getSource().sendFailure(fail("Invalid amount: balances are bounded to [0, " + Money.formatPlain(Money.MAX_BALANCE_CENTS) + "]."));
            case LEDGER_UNAVAILABLE -> ctx.getSource().sendFailure(fail("Ledger unavailable, nothing changed."));
        }
        return 0;
    }

    private static int grantStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        CommandSupport.Target t = target(ctx, rt);
        Account a = rt.economy.getOrCreate(t.uuid(), t.name());
        if (a.grant == Account.Grant.GRANTED) {
            ctx.getSource().sendFailure(fail(t.name() + " already received the starting capital."));
            return 0;
        }
        long cents = ServerConfig.startingCapitalCents();
        Transactions.Outcome outcome = rt.transactions.grantStart(a, cents, "start-override", counterpart(ctx.getSource()), LedgerType.ADMIN_ADD);
        if (outcome.result() == Result.SUCCESS || outcome.result() == Result.VETOED) {
            ctx.getSource().sendSuccess(() -> ok("Starting capital paid to " + t.name() + " (" + Money.format(outcome.delta(), CommonConfig.currencySymbol()) + ")."), true);
            return 1;
        }
        ctx.getSource().sendFailure(fail(outcome.result() == Result.LEDGER_UNAVAILABLE ? "Ledger unavailable, nothing changed." : "Starting capital could not be paid: " + outcome.result()));
        return 0;
    }
}
