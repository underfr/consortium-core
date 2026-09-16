Consortium Core, the server-authoritative economy core of The Consortium modpack (NeoForge 1.21.1, Java 21).

Ships on both sides (`side = "both"` in the pack): the server runs the ledger, the price table, the Delivery Terminal
transaction, the commands and the API; the client only draws the terminal screen and the credits HUD. The KubeJS
phase engine of the pack keeps owning phases, quotas, stages, charters and quests; the terminal feeds it through the
`contribute_command` follow-up (default `consortium contribute {item} {count}`).

The pack references the attached jar with `packwiz url add` (see the README).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
