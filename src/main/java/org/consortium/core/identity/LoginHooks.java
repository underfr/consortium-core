package org.consortium.core.identity;

import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.Result;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.LedgerType;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Transactions;
import org.consortium.core.monitoring.Notifier;

/**
 * {@code PlayerLoggedInEvent} handling (specification 6): account upkeep, then the starting capital with the
 * same-connection check for accounts that are neither {@code GRANTED} nor {@code DENIED}.
 */
public final class LoginHooks {
    private LoginHooks() {
    }

    public static void onLogin(ConsortiumRuntime rt, ServerPlayer player) {
        long now = rt.now();
        Account account = rt.economy.getOrCreate(player.getUUID(), player.getGameProfile().getName());
        account.name = player.getGameProfile().getName();
        if (account.firstLogin == 0) {
            account.firstLogin = now;
        }
        account.lastSeen = now;
        account.pruneDailyPaid(rt.transactions.utcDay(now));
        account.pruneDailyBought(rt.transactions.utcDay(now));
        rt.economy.touch();

        rt.notifier.showBootMessages(player);
        rt.notifyBalance(account.uuid, account.balance, 0, "login");
        rt.board.sendTo(player);

        if (account.grant != Account.Grant.NONE) {
            return;
        }
        startingCapital(rt, player, account);
    }

    private static void startingCapital(ConsortiumRuntime rt, ServerPlayer player, Account account) {
        long cents = ServerConfig.startingCapitalCents();
        String hash = null;
        if (!CommonConfig.identityEnabled()) {
            ConsortiumCore.LOGGER.warn("Identity check disabled: starting capital granted to {} without a connection check", account.name);
        } else {
            String normalised = IpFingerprint.normalize(player.getIpAddress(), CommonConfig.ipv6PrefixBits());
            if (normalised == null) {
                ConsortiumCore.LOGGER.warn("Connection address of {} is unknown: starting capital granted without a connection check", account.name);
            } else {
                hash = IpFingerprint.hash(rt.salt.load(), normalised);
            }
        }
        if (hash != null) {
            IdentityData.Served served = rt.identity.served(hash);
            if (served != null && !served.firstUuid().equals(account.uuid)) {
                rt.identity.link(account.uuid, hash);
                if (ServerConfig.multiAccountGrant() == ServerConfig.MultiAccountGrant.DENY) {
                    Transactions.Outcome denied = rt.transactions.denyStart(account, "same connection as an account already paid");
                    if (denied.ok()) {
                        Notifier.tell(player, "Starting capital was already paid to an account on your connection. Sharing a connection? Ask a moderator.");
                        rt.notifier.informOps("Starting capital denied to " + account.name + ": same connection as an existing account (/credits grant-start to override)");
                    }
                    return;
                }
                Transactions.Outcome paid = rt.transactions.grantStart(account, cents, "starting_capital", "system", LedgerType.STARTING_CAPITAL);
                if (paid.ok()) {
                    Notifier.tell(player, "Welcome to the Consortium. Your account was opened with " + Money.format(paid.delta()) + ".");
                }
                rt.notifier.alertOps("ALERT multi-account: " + account.name + " shares a connection with an account already paid; starting capital granted and flagged (GRANT_AND_FLAG)");
                return;
            }
        }
        Transactions.Outcome outcome = rt.transactions.grantStart(account, cents, "starting_capital", "system", LedgerType.STARTING_CAPITAL);
        if (outcome.result() == Result.LEDGER_UNAVAILABLE) {
            Notifier.tell(player, "The ledger is unavailable right now; your starting capital will be paid at a later login.");
            return;
        }
        if (hash != null) {
            rt.identity.markServed(hash, account.uuid, rt.now());
        }
        if (outcome.ok()) {
            Notifier.tell(player, "Welcome to the Consortium. Your account was opened with " + Money.format(outcome.delta()) + ".");
        } else if (outcome.result() == Result.VETOED) {
            Notifier.tell(player, "Welcome to the Consortium. Your account is open.");
        }
    }
}
