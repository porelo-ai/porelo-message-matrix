# Issues

The index, grouped by state; within a group the order is the priority. The number is identity, not
sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx27"></a>[MX27](MX27-The-Homeserver-Starts-Without-A-Container.md) | S | - | The integration tests start Tuwunel's own binary where a task names it, so they run inside a Porelo task, as [PJ49](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj49) asks |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx24"></a>[MX24](MX24-The-Room-Outlives-The-Machine.md) | M | - | `backup <dir>` and `restore <dir>` for the host's playbook, so the local homeserver's conversation outlives a rented host, as [PJ45](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj45) plans; `setup` takes `@porelo` back after a restore |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx19"></a>[MX19](MX19-Clear-Leaves-A-Member-Of-Another-Project.md) | S | - | Clearing one project never cuts a person off from another project on the same homeserver |
| <a id="mx20"></a>[MX20](MX20-The-Local-Homeserver-Is-Proven-The-Accounts-Own.md) | M | - | A token for the account's own local homeserver reaches it and no other account's listener on the same port |
| <a id="mx23"></a>[MX23](MX23-The-Transport-Takes-Its-Tokens-On-Its-Input.md) | M | porelo's [B206](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b206) | The transport's tokens never in its environment: Porelo hands them on the transport's input, so no moment at its start leaves them readable |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx28"></a>[MX28](MX28-A-Task-Account-Per-Project.md) | - | - | Two projects' tasks never share one account when their names run together, `porelo-a-b-c` from `a-b`/`c` and `a`/`b-c` |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx29"></a>[MX29](MX29-The-Homeserver-On-A-Socket-Of-The-Accounts-Own.md) | - | - | The account's own homeserver behind a unix socket only it can open, across this transport, porelo and porelo-frontend, closing the window [MX20](README.md#mx20) leaves |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx12"></a>[MX12](MX12-Several-Machines-On-One-Homeserver.md) | L | - | Several machines provision on one central homeserver, each with no more than its own rights, and their tasks share one conversation |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="mx26"></a>[MX26](README.md#mx26) | Jackson Without Its Known Advisories | jackson-core and jackson-databind 2.22.3 in the transport, past the seven advisories published against 2.22.1 |
| <a id="mx25"></a>[MX25](README.md#mx25) | Publish Without A Downloaded Client | The packages published with `curl` through `.github/scripts/artifactory.sh`, both checksums sent; no JFrog client runs beside the token |
| <a id="mx22"></a>[MX22](README.md#mx22) | A Homeserver Elsewhere Gets Its Registration Token | The setting `registration_token_file`, read by `setup`, `enroll` and `join` and checked by `settings`: absolute, only for a homeserver elsewhere, readable by the account alone |
| <a id="mx21"></a>[MX21](README.md#mx21) | The Transport Keeps Its Tokens From The Account | The running transport not dumpable, so its environment and memory belong to root; the moment before it, measured, is the accepted risk in `doc/decisions.md` |
| <a id="mx18"></a>[MX18](README.md#mx18) | Reformatted To The Shared Style | The shared `.editorconfig` and `spotless:check` in `validate`, the Java reformatted once in `2e795eb`, which `git blame` skips through `.git-blame-ignore-revs` |
| <a id="mx16"></a>[MX16](README.md#mx16) | The Code Read Against Its Skills | All of the code read against seven skills, its findings fixed in `433cebe`, each with its test or why it has none |
| <a id="mx17"></a>[MX17](README.md#mx17) | The Transport Is Renamed Porelo | The transport, its packages, program, settings, directories and accounts say Porelo, installed afresh; a documents test keeps the old name out of every tracked file |
