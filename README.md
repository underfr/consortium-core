# Consortium Core

Server-authoritative economy core for **The Consortium**, a private NeoForge 1.21.1 modpack.
Mod id `consortium`, package `org.consortium.core`, MIT license.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

## Division of responsibilities

The mod owns **money, prices, the terminal, the ledger and the HUD**. The pack's KubeJS phase engine
(`pack/kubejs/server_scripts/consortium_*.js`) owns **phases, quotas, stages, charters and quests**, the boss bar and the
whole `/consortium` command tree. The mod keeps no phase counter: after every committed delivery it runs the server
config template `contribute_command` (default `consortium contribute {item} {count}`) once per delivered item id as the
console, which is exactly the engine's `/consortium contribute <item> <count>` command. The mod's own commands live
under `/credits`, `/prices` and `/ccore`, never under `/consortium`, and its KubeJS binding is named `ConsortiumCore`
so the engine's script-level `Consortium` object is untouched.

## What it does (0.2.0)

The design lives in `docs/CONSORTIUM_CORE.md` (v0.1) and `docs/CONSORTIUM_CORE_V02.md` (v0.2) at the repository root
and follows `docs/PROJECT_RULES.md` section 4.2. Implemented in this build:

- **Ledger**: `world/consortium/ledger/YYYY-MM-DD.jsonl`, write-ahead (no line, no money), flushed per line, never
  replayed; boot scan marks a rolled-back tail with a `ROLLBACK` line (earlier markers never nest, and `last_seq` is
  saved right after the scan); 200-line in-memory tail for `/ccore ledger tail`.
- **Accounts** in `world/data/consortium_economy.dat` (balance, lifetime delivered, rank credit, daily paid units,
  grant state), saved with the vanilla save pass so money and items roll back together.
- **Starting capital and anti-multi-account**: HMAC-SHA256 of the normalised address (IPv6 collapsed to /64) with the
  salt in `<server root>/consortium/ip-salt.bin`; hashes in `world/data/consortium_identity.dat`, no address ever stored.
- **Price table** from `data/<namespace>/consortium_prices/*.json` (families keyed by material, members with weights,
  `#tag` members expanded once the tags are bound), config defaults, `/prices set` overrides layered on top and folded
  automatically when the datapack catches up, every change logged as `PRICE_CHANGE` and announced.
- **Market**: exponential saturation with lazy decay, exact integral per delivery (path independent), floor, clock
  clamp, per-player daily cap, quota-only families (`base` 0).
- **Commands** behind NeoForge permission nodes (`consortium.credits.balance`, `consortium.admin.credits`, ...;
  LuckPerms resolves them):
  - `/credits`, `/credits top [n]`, `/credits balance <player>`, `/credits add|take|set <player> <amount> <reason...>`,
    `/credits grant-start <player>`;
  - `/prices get <key>`, `/prices list [page]`, `/prices set <key> <base> [half_volume] <reason...>`,
    `/prices clear <key> <reason...>`, `/prices reset <key> <reason...>`;
  - `/ccore` (= `/ccore version`), `/ccore ledger tail [n] [filter]`, `/ccore report [weekly|daily]`, `/ccore board`,
    `/ccore shop list [player]`, `/ccore shop open [player]` (node `consortium.admin.shop`, level 2), `/ccore shop buy
    <key> [player]` (node `consortium.admin.shop_buy`, level 4: it spends another player's credits, an owner and
    harness tool), `/ccore identity forget <player>`, `/ccore identity purge`, and `/ccore selftest`, which exists only when the JVM
    runs with `-Dconsortium.selftest=true` (test servers): it runs an API deposit, a withdraw, an overdraft refusal and a
    full terminal delivery on the fake account `SelfTest`, prints every ledger line and the market move, and ends with
    `SELFTEST OK` or the list of failed checks; it writes real ledger lines and runs the real `contribute_command`.
- **Monitoring**: alert rules 1 to 4, weekly and daily report from in-memory aggregates, Simple Discord Link bridge.
- **API**: `org.consortium.core.api.ConsortiumAPI` (bound as `ConsortiumCore` in KubeJS server scripts: balance,
  credit, debit, rank credit, price views with their `charterFamily`, format, announce, `publishBoard`, `boardPhase`,
  `firstLogin`, `grant`) and the events `BalanceChangeEvent` (also posted before a shop purchase, where a listener may
  take more but never less), `DeliveryQuoteEvent` (each line carries `getCharterFamily()`: `raw`, `power`, `transport`
  or `neutral` from the price family's `charter_family`), `DeliveryEvent` and `ShopPurchaseEvent` (after a committed
  purchase: player, key, cents, tx, item or rendered command).
- **Delivery service** (`org.consortium.core.terminal.DeliveryService`): the server-side quote and confirm of the
  terminal (refusal rules, per-family aggregation, quote event, tolerance check, one-tick transaction, then the
  `contribute_command` follow-up per delivered item id). Creative-mode players are refused by the block and by the
  confirm ("Switch to survival to deliver").
- **Delivery Terminal** (`consortium:delivery_terminal`): a plain block (no block entity, no inventory, no item
  handler capability, so hoppers, pipes and AE2 cannot feed it; a `FakePlayer` cannot open it). Right-click opens a
  menu whose 27-slot grid is transient and owned by the menu (given back to the player on close, dropped at the feet
  of a disconnected player). The server re-quotes the grid at most once per tick and pushes `DeliveryQuote`; the
  screen shows the balance, one row per family (units, average unit price, subtotal), grey quota-only rows, red
  refused slots with the reason in the tooltip, the total and the Deliver / Cancel buttons. Deliver sends
  `DeliveryConfirm` with the quote nonce: a reused nonce is ignored (click spam), a stale one gets the current quote
  back, a matching one runs the transaction and the receipt goes to chat. No recipe: staff places it with `/give`.
- **Delivery Station and quota board** (v0.2): the terminal is the controller of a wall screen of **Display Panels**
  (`consortium:display_panel`, recipe `GGG / IRI / III` with glass, iron ingots and redstone for 4 panels). Panels
  stacked above the terminal, in its plane, form a rectangle of up to 7 x 4 (bottom row extended left and right
  alternately so a symmetric build stays centred; a row counts only when complete); formation runs on placement and
  removal events only, never per tick, and the terminal's block entity keeps the rectangle in NBT. The client draws
  the **quota board** over the rectangle: header (phase name, completion or "Complete", season day, page), one row per
  quota line with the item icon, the label, a `15,000 / 40,000` counter, a progress bar coloured red / amber / green
  and a check mark when done; pages cycle every 6 seconds when the lines do not fit. The board shows exactly what the
  pack's phase engine publishes through `ConsortiumCore.publishBoard(json)` (a canonical snapshot saved in
  `world/data/consortium_board.dat`, synced on every change and at login); the mod keeps no phase state of its own.
  `/ccore board` prints the current snapshot.
- **Chapters guards** (v0.2, only when Chapters is present; `config/consortium-server.toml` `[guards]`
  `placement_guard` and `instant_audit`, both on by default): a block whose item Chapters locks for the placer's team
  cannot be placed (the placement event is cancelled, the block and the held stack are restored, an action bar line
  says "... is not unlocked for your team"); a fake player such as a Create deployer, which has no team and no stages,
  may place a gated block only when one of its gating stages is `consortium:phase_k` with `k` at most the phase of the
  last quota board snapshot (every gated block is locked before the first publish). A locked item that lands in a
  player's inventory (shift-click out of a chest, hotbar swap, `/give`, a hopper) is dropped in the same tick through
  Chapters' own audit instead of its one-second sweep, with "... is locked for your team and was dropped" at most once
  per second. Neither guard logs per event; the weekly report ends with "Guards since boot: N placements refused, M
  instant audits".
- **Party stage copy** (v0.2, only when FTB Teams is present): when a player creates a party, the `consortium:` stages
  of their personal team are copied onto the new party inside FTB Teams' `TeamEvent.CREATED`, before the creator is
  moved in and before Chapters audits their inventory, so nothing unlocked by the phase stages is dropped ("Copied N
  consortium stages to party <name>" in the log).
- **Shop** (v0.2): the money sinks of PROJECT_RULES 4.1 and 4.2 sold for credits. The catalogue comes from
  `data/<namespace>/consortium_shop/*.json` (entry fields `name`, `description`, `item` xor `command`, `icon`,
  `price`, `daily_limit`, `stage`, `phase`; the pack ships `starter.json` with the four chunk loaders behind the
  `consortium:phase_3` stage and ten extra FTB Chunks claim chunks, 2 per day, all prices placeholders). An invalid
  entry is skipped with a WARN, an item entry the Consortium buys is refused ("buy-then-sell loops are forbidden"),
  and an item entry Chapters locks without the matching `stage` warns at load. The terminal screen gets a **Shop**
  button (active while the grid is empty, because opening the shop closes the terminal menu and hands the grid back)
  that opens the shop screen: a scrolled list with the icon, name, price and a status line (available, not enough
  credits, not unlocked for your team, available from phase N, daily limit reached, N of M bought today), a tooltip
  with the item, the description, the price, the full status line and the "resets at 00:00 UTC" note for limited
  entries, **Buy** and **Back**. A purchase runs in one tick, in this order: the refusals
  (ledger unavailable, creative, dead, unknown key, stage not held, Chapters lock, phase, daily limit, priced item,
  unusable command placeholder, funds), the ledger line `PURCHASE` (`reason` = the key, `counterpart` = `shop:<key>`,
  extras `entry_name`, `item` or `command`, `terminal`, `by`) flushed before the balance moves, the daily counter and
  a **synchronous save of the saved data** (money durable first: a crash can never hand the item and the money back
  together), then the effect (the stack into the inventory with the overflow dropped, or the command as the console
  with `{player}`, `{uuid}`, `{tx}` validated before rendering), then `ShopPurchaseEvent`, the result line and a fresh
  catalogue. Every catalogue view carries a nonce that one purchase consumes (a double click buys once). A boot
  `ROLLBACK` notice lists the `PURCHASE` lines of the range with the `/credits take` hint.
- **Charter families and newcomer accessors** (v0.2): every price family carries `charter_family` (`raw`, `power`,
  `transport`, `neutral`), shown by `/prices get` and handed to `DeliveryQuoteEvent` listeners; `ConsortiumAPI.firstLogin`
  and `grant` let the pack's `consortium_money.js` apply the PROGRESSION 9.1 charter modifiers and the PROGRESSION 12
  newcomer bonus in one `setMultiplier` per line. The mod itself applies no modifier.
- **Payloads** (`org.consortium.core.network`, registrar version 1): `BalanceSync` (S2C, on login, respawn,
  dimension change and every balance change), `DeliveryQuote` (S2C), `DeliveryConfirm` (C2S), `BoardSync` (S2C, on
  every accepted board publish and at login), `ShopOpen` (C2S), `ShopCatalog` (S2C), `ShopBuy` (C2S), `ShopResult`
  (S2C), `TerminalOpen` (C2S, the shop's Back button).
- **Client HUD**: "Credits: 412.55 CC" in the top-left corner by default (`config/consortium-client.toml`:
  `hud.enabled`, `corner`, `offset_x`, `offset_y`, `delta_seconds`), the last change shown in green or red for a few
  seconds, hidden until the first sync from the current server, toggled with `J` (rebindable, category Consortium).

### The contribute follow-up

`config/consortium-server.toml` (`world/serverconfig/` overrides it per world), `[terminal] contribute_command`, default
`consortium contribute {item} {count}`. Placeholders: `{item}` registry id (`minecraft:iron_ingot`), `{count}` integer
(the counts of one item id in a delivery are merged), `{player}` name, `{uuid}`, `{tx}`. The command runs as the console
with suppressed output, on the server thread, right after the ledger commit; a failure (unknown command because the
engine is absent, bad argument, an exception inside the script) is logged at WARN once per minute and never affects the
delivery. Set it to an empty string to disable the hook. Every known placeholder the template uses is validated before
rendering (`{player}` as a profile name, `{uuid}` in its canonical form, `{tx}` as 8 hex characters, `{item}` as a
namespaced id, `{count}` as digits): a value that fails is never rendered and the run is refused with the same WARN.

## Targets

| Component | Version |
|---|---|
| Minecraft | 1.21.1 (locked) |
| NeoForge | 21.1.250 |
| Java | 21 (toolchain, auto-provisioned) |
| Gradle | 9.2.1 (wrapper) |
| ModDevGradle | 2.0.147 |

Optional compile-time integrations, pinned to the versions the pack ships: KubeJS 2101.7.2-build.377, Rhino
2101.2.7-build.85, FTB Library 2101.1.36, FTB Teams 2101.1.11, FTB Quests 2101.1.35, Architectury 13.0.11, Simple
Discord Link 3.4.4 (server only), Chapters 1.1 (Modrinth-only, resolved through the Modrinth Maven at
`https://api.modrinth.com/maven`, no token). None of them is bundled; the mod must keep loading when they are absent.

## Building

No Gradle installation is needed, the wrapper downloads Gradle 9.2.1. Gradle itself runs on whatever JDK is on `PATH`
(JDK 17 to 26; the dev box has JDK 25) and compiles the mod with a JDK 21 toolchain that the foojay resolver downloads
on first use. The first build also decompiles NeoForge (a few minutes and about 70 MB under `build/moddev`); later
builds take seconds.

```sh
# Git Bash
./gradlew build --console=plain
# PowerShell
.\gradlew.bat build --console=plain
```

Output: `build/libs/consortium-<version>.jar`. `./gradlew test` runs the pure-Java unit tests (JUnit 5).

## Running a dev server

```sh
mkdir -p run
printf 'eula=true\n' > run/eula.txt
printf 'server-port=25577\nonline-mode=false\n' > run/server.properties
./gradlew runServer --console=plain      # headless (--nogui); type "stop" to end
```

`run/eula.txt` with `eula=true` is required or the server exits immediately. The `localRuntime` dependencies in
`build.gradle` put the optional mods on the dev mod path; comment them out for a vanilla-only run.
`./gradlew runClient` starts a client (needs a display). Add `-Dconsortium.selftest=true` to the server JVM
arguments to get `/ccore selftest` on a test server.

## Layout

```
build.gradle, settings.gradle, gradle.properties   Gradle build (ModDevGradle)
gradle/wrapper/                                    Gradle 9.2.1 wrapper (jar verified against the official sha256)
.github/workflows/ci.yml, release.yml              CI build on push and PR; GitHub Release with the jar on a v*.*.* tag
.github/RELEASE_NOTES.md                           notes attached to every release (disclaimer included)
src/main/java/org/consortium/core/                 mod sources (economy, pricing, identity, command, monitoring, api, compat, terminal, board, shop, network, client)
src/main/templates/META-INF/neoforge.mods.toml     mod metadata template, expanded at build time
src/main/resources/kubejs.bindings.txt             KubeJS binding of the API as "ConsortiumCore" in server scripts
src/main/resources/assets/consortium/              blockstates, models, placeholder textures and en_us lang of the terminal and the display panel
src/main/resources/data/                           loot tables, mineable tags and the display panel recipe
src/test/java/                                     JUnit 5 unit tests (no Minecraft classes): money, curve, ledger, identity, reports, command template and placeholder rules, shop entry validation, board JSON, board layout, screen formation, guard rules
```

## Shipping

CI (`.github/workflows/ci.yml`) builds every push to `main` and every pull request on JDK 21. Pushing a tag such as
`v0.1.0` runs `.github/workflows/release.yml`, which builds the jar and creates the GitHub Release with
`build/libs/consortium-<version>.jar` attached and `.github/RELEASE_NOTES.md` as the body.

The pack references that release asset with packwiz (side `both`: the client needs the screen and the HUD, which is
what `packwiz url add` writes by default):

```sh
cd pack
../tools/packwiz/packwiz.exe url add "Consortium Core" \
  https://github.com/<owner>/consortium-core/releases/download/v0.1.0/consortium-0.1.0.jar \
  --meta-folder mods --meta-name consortium-core
../tools/packwiz/packwiz.exe refresh
```

This writes `pack/mods/consortium-core.pw.toml` with `side = "both"` and the sha512 of the jar. Bumping the mod means:
raise `mod_version` in `gradle.properties`, tag `v<version>`, then run the same `packwiz url add` with the new URL
(it replaces the metadata file) and `packwiz refresh` so the launcher picks up the new hash. See
`docs/CONSORTIUM_CORE.md` section 10.

## License

MIT, see `LICENSE`.
