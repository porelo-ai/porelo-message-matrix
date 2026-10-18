# Issues

The index, grouped by state; within a group the order is the priority. The number is identity, not
sequence.

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx21"></a>[MX21](MX21-The-Transport-Keeps-Its-Tokens-From-The-Account.md) | S | - | The running transport not dumpable, so no other process of the account reads its tokens from `/proc`, as porelo's [B23](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b23) did for the daemon |
| <a id="mx22"></a>[MX22](MX22-A-Homeserver-Elsewhere-Gets-Its-Registration-Token.md) | S | - | A homeserver elsewhere set up through Porelo, the registration token read from a file the project's settings name |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx19"></a>[MX19](MX19-Clear-Leaves-A-Member-Of-Another-Project.md) | - | - | Clearing one project never cuts a person off from another project on the same homeserver |
| <a id="mx20"></a>[MX20](MX20-The-Local-Homeserver-Is-Proven-The-Accounts-Own.md) | - | - | A token for the account's own local homeserver reaches it and no other account's listener on the same port |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx12"></a>[MX12](MX12-Several-Machines-On-One-Homeserver.md) | L | - | Several machines provision on one central homeserver, each with no more than its own rights, and their tasks share one conversation |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="mx18"></a>[MX18](README.md#mx18) | Reformatted To The Shared Style | The shared `.editorconfig` and `spotless:check` in `validate`, the Java reformatted once in `2e795eb`, which `git blame` skips through `.git-blame-ignore-revs` |
| <a id="mx16"></a>[MX16](README.md#mx16) | The Code Read Against Its Skills | All of the code read against seven skills, its findings fixed in `433cebe`, each with its test or why it has none |
| <a id="mx17"></a>[MX17](README.md#mx17) | The Transport Is Renamed Porelo | The transport, its packages, program, settings, directories and accounts say Porelo, installed afresh; a documents test keeps the old name out of every tracked file |
