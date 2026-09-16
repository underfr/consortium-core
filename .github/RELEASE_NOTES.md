Consortium Core, the server-authoritative economy core of The Consortium modpack (NeoForge 1.21.1, Java 21).

Ships on both sides (`side = "both"` in the pack): the server runs the ledger, the price table, the Delivery Terminal
transaction, the Delivery Station formation, the shop, the commands and the API; the client only draws the terminal and
shop screens, the quota board over the Display Panels and the credits HUD. The KubeJS phase engine of the pack keeps
owning phases, quotas, stages, charters and quests; the terminal feeds it through the `contribute_command` follow-up
(default `consortium contribute {item} {count}`) and the engine pushes the quota board through
`ConsortiumCore.publishBoard`. With Chapters present the mod refuses placing a block the placer's team has not unlocked
and drops a locked item the tick it lands in an inventory (server config `[guards]`); with FTB Teams present a new party
inherits the `consortium:` stages of its creator's personal team before Chapters audits the creator.

The shop (v0.2) sells the money sinks of the rulebook for credits from `data/<namespace>/consortium_shop/*.json`: item
entries (never an item the Consortium buys) and console-command entries, each with a price, optional Chapters stage,
phase and daily limit. The terminal screen's Shop button opens it with an empty grid; a purchase writes the `PURCHASE`
ledger line and saves the balance before the item or the command is delivered, so a crash never hands both back. Price
families carry a `charter_family` and the API exposes `firstLogin` and `grant`, which the pack's `consortium_money.js`
uses for the charter and newcomer delivery modifiers.

The pack references the attached jar with `packwiz url add` (see the README).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
