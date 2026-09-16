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

## What it does (0.1.0)

The design lives in `docs/CONSORTIUM_CORE.md` at the repository root and follows `docs/PROJECT_RULES.md` section 4.2.
Implemented in this build:

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
  - `/ccore` (= `/ccore version`), `/ccore ledger tail [n] [filter]`, `/ccore report [weekly|daily]`,
    `/ccore identity forget <player>`, `/ccore identity purge`, and `/ccore selftest`, which exists only when the JVM
    runs with `-Dconsortium.selftest=true` (test servers): it runs an API deposit, a withdraw, an overdraft refusal and a
    full terminal delivery on the fake account `SelfTest`, prints every ledger line and the market move, and ends with
    `SELFTEST OK` or the list of failed checks; it writes real ledger lines and runs the real `contribute_command`.
- **Monitoring**: alert rules 1 to 4, weekly and daily report from in-memory aggregates, Simple Discord Link bridge.
- **API**: `org.consortium.core.api.ConsortiumAPI` (bound as `ConsortiumCore` in KubeJS server scripts: balance,
  credit, debit, rank credit, price views, format, announce) and the events `BalanceChangeEvent`,
  `DeliveryQuoteEvent`, `DeliveryEvent`.
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
- **Payloads** (`org.consortium.core.network`, registrar version 1): `BalanceSync` (S2C, on login, respawn,
  dimension change and every balance change), `DeliveryQuote` (S2C), `DeliveryConfirm` (C2S).
- **Client HUD**: "Credits: 412.55 CC" in the top-left corner by default (`config/consortium-client.toml`:
  `hud.enabled`, `corner`, `offset_x`, `offset_y`, `delta_seconds`), the last change shown in green or red for a few
  seconds, hidden until the first sync from the current server, toggled with `J` (rebindable, category Consortium).

### The contribute follow-up

`config/consortium-server.toml` (`world/serverconfig/` overrides it per world), `[terminal] contribute_command`, default
`consortium contribute {item} {count}`. Placeholders: `{item}` registry id (`minecraft:iron_ingot`), `{count}` integer
(the counts of one item id in a delivery are merged), `{player}` name, `{uuid}`, `{tx}`. The command runs as the console
with suppressed output, on the server thread, right after the ledger commit; a failure (unknown command because the
engine is absent, bad argument, an exception inside the script) is logged at WARN once per minute and never affects the
delivery. Set it to an empty string to disable the hook.

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
Discord Link 3.4.4 (server only). None of them is bundled; the mod must keep loading when they are absent.

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
src/main/java/org/consortium/core/                 mod sources (economy, pricing, identity, command, monitoring, api, compat, terminal, network, client)
src/main/templates/META-INF/neoforge.mods.toml     mod metadata template, expanded at build time
src/main/resources/kubejs.bindings.txt             KubeJS binding of the API as "ConsortiumCore" in server scripts
src/main/resources/assets/consortium/              blockstate, models, placeholder textures and en_us lang of the terminal
src/main/resources/data/                           loot table and mineable tags of the terminal
src/test/java/                                     JUnit 5 unit tests (no Minecraft classes): money, curve, ledger, identity, reports, template
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
