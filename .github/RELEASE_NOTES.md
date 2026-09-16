Consortium Core, the server-authoritative economy core of The Consortium modpack (NeoForge 1.21.1, Java 21).

Ships on both sides (`side = "both"` in the pack): the server runs the ledger, the price table, the Delivery Terminal
transaction, the Delivery Station formation, the shop, the presence module, the commands and the API; the client only
draws the terminal and shop screens, the quota board over the Display Panels and the credits HUD. The KubeJS phase
engine of the pack keeps owning phases, quotas, stages, charters and quests; the terminal feeds it through the
`contribute_command` follow-up (default `consortium contribute {item} {count}`) and the engine pushes the quota board
through `ConsortiumCore.publishBoard`. With Chapters present the mod refuses placing a block the placer's team has not
unlocked and drops a locked item the tick it lands in an inventory (server config `[guards]`); with FTB Teams present a
new party inherits the `consortium:` stages of its creator's personal team before Chapters audits the creator.

The shop (v0.2) sells the money sinks of the rulebook for credits from `data/<namespace>/consortium_shop/*.json`: item
entries (never an item the Consortium buys) and console-command entries, each with a price, optional Chapters stage,
phase and daily limit. The terminal screen's Shop button opens it with an empty grid; a purchase writes the `PURCHASE`
ledger line and saves the balance before the item or the command is delivered, so a crash never hands both back. Price
families carry a `charter_family` and the API exposes `firstLogin` and `grant`, which the pack's `consortium_money.js`
uses for the charter and newcomer delivery modifiers.

The presence module (v0.3, server config `[presence]`) puts the LuckPerms rank prefix, suffix and colour on the chat
display name (so on chat, join, leave, death, `/say`, `/me` and `/tell` lines) and on the tab list entry, renders a
per-viewer tab header and footer (server name, phase and day, the viewer's group and credits, TPS, MSPT, online count)
and a two-line server list MOTD (server name and phase, TPS and online count), every format with `&` and section-sign
colour codes and placeholders. Chat stays signed: the sender part comes from `PlayerEvent.NameFormat`, the body is only
ever styled through `ServerChatEvent.setMessage` (unsigned content only, no "Modified" tag, report log untouched) and
the event is never cancelled or rebroadcast as a system message. Rank changes refresh live through LuckPerms'
`UserDataRecalculateEvent`; vanished players (Vanishmod) are left out of `{online}`. `/ccore presence preview <player>`
prints the rendered lines as plain text, `/ccore presence reload` re-reads the config for everyone (node
`consortium.admin.presence`). LuckPerms (`net.luckperms:api`, compile-only, optional dependency `[5.4.150,)`) and
Vanishmod (one method handle, no dependency) are both optional: without them names render plain and everyone counts as
online.

The pack references the attached jar with `packwiz url add` (see the README).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
