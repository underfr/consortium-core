package org.consortium.core.terminal;

import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.economy.Transactions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bridge from a committed delivery to the KubeJS phase engine of the pack (specification 5, follow-ups): once per
 * delivered item id the SERVER config template {@code contribute_command} (default
 * {@code consortium contribute {item} {count}}) is rendered and run as the console through {@link CommandRunner}, on
 * the server thread, right after the ledger commit. Output is suppressed; a failure (unknown command, bad argument,
 * a placeholder value that fails its rule, an exception inside the engine) is logged at WARN once per minute and
 * never reaches the delivery: the money and the items already moved together.
 */
public final class ContributeHook {
    private ContributeHook() {
    }

    /** Runs the template once per {@link Transactions.ItemCount} of the committed delivery. Never throws. */
    public static void afterDelivery(ConsortiumRuntime rt, Transactions.DeliveryRequest request, String tx) {
        String template;
        try {
            template = ServerConfig.contributeCommand();
        } catch (Throwable t) {
            template = null;
        }
        if (template == null || template.isBlank() || rt == null || rt.server == null) {
            return;
        }
        for (Transactions.DeliveryLine line : request.lines()) {
            for (Transactions.ItemCount ic : line.items()) {
                if (ic.count() <= 0) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                values.put("item", ic.id());
                values.put("count", Integer.toString(ic.count()));
                values.put("player", request.name() == null ? "" : request.name());
                values.put("uuid", request.player() == null ? "" : request.player().toString());
                values.put("tx", tx == null ? "" : tx);
                CommandRunner.Run run = CommandRunner.execute(rt, template, values);
                if (!run.ok()) {
                    CommandRunner.warnFailure(rt, "contribute_command", tx, run.command() == null ? template : run.command(), run.failure());
                }
            }
        }
    }
}
