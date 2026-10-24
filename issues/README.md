# Issues

The index, grouped by state; within a group the order is the priority. The number is identity, not
sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx24"></a>[MX24](MX24-The-Room-Outlives-The-Machine.md) | M | - | `backup <dir>` and `restore <dir>` for the host's playbook, so the local homeserver's conversation outlives a rented host, as [PJ45](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj45) plans; `setup` takes `@porelo` back after a restore |
| <a id="mx34"></a>[MX34](MX34-The-Homeserver-Starts-On-A-Large-Machine.md) | S | - | The local homeserver starts, and `backup` and `restore` run, on a machine of any size: its pools, caches and container are bounded instead of growing with the cores |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx37"></a>[MX37](MX37-The-Homeserver-Says-Why-It-Did-Not-Start.md) | XS | - | When `setup` gives up waiting for the local homeserver, a person reads why in Porelo's start from the homeserver's own words, never a secret; `describe` says what a start waits on, for porelo's [B268](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b268) |
| <a id="mx35"></a>[MX35](MX35-A-Person-Knows-The-Rooms-Helpers.md) | M | - | A person reads the room's two helpers by name, and a line naming only them wakes no task: it is marked `helpers` for Porelo to answer |
| <a id="mx36"></a>[MX36](MX36-A-Backup-Runs-While-Tasks-Poll.md) | S | - | `backup <dir>` succeeds within its bound while Porelo's poll loops run, instead of starving behind overlapping shared holds of `keep.lock` |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx23"></a>[MX23](MX23-The-Transport-Takes-Its-Tokens-On-Its-Input.md) | M | porelo's [B206](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b206) | The transport's tokens never in its environment: Porelo hands them on the transport's input, so no moment at its start leaves them readable |
| <a id="mx29"></a>[MX29](MX29-The-Homeserver-On-A-Socket-Of-The-Accounts-Own.md) | - | - | After [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39). The account's own homeserver behind a unix socket only it can open, across this transport, porelo and porelo-frontend, closing the window the check of the listener's owner leaves |

## Analyzed

| # | Size | Open questions | Blocked by | What it covers |
|---|---|---|---|---|
| <a id="mx39"></a>[MX39](MX39-A-Backup-Is-Its-Own.md) | S | 0 | - | A copy `backup` reports as made holds the database and positions of that one backup: no late earlier snapshot taken for it, and no backup emptying a directory another one filled |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx30"></a>[MX30](MX30-Only-A-Person-Speaks-As-A-Person.md) | M | - | After [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39). On a central homeserver a task takes words as a person's only from the people the project names, so the operator's direct chat with a task is the operator's own channel and no other machine's account speaks in it |
| <a id="mx38"></a>[MX38](MX38-A-Room-Shows-No-Helper.md) | XL | - | A person sees no helper account in the room: a project's application service acts through its task accounts only, a lobby the people never see admits them, and neither the administrator nor a relay is a member; splits into parts of L or smaller |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="mx27"></a>[MX27](README.md#mx27) | The Homeserver Starts Without A Container | The integration tests start Tuwunel's own binary where `MATRIX_TEST_TUWUNEL` names it, so they run inside a Porelo task |
| <a id="mx19"></a>[MX19](README.md#mx19) | Clear Leaves A Member Of Another Project | `clear --project` deactivates only the accounts of the project's room that are in no other project's room, and names each one it kept |
| <a id="mx20"></a>[MX20](README.md#mx20) | The Local Homeserver Is Proven The Account's Own | A token for the account's own local homeserver goes only to a loopback listener of that account; another account's listener is refused with 78 |
| <a id="mx28"></a>[MX28](README.md#mx28) | A Task Account Per Project | `enroll` and `retire` refuse with 78 a task account that is in another project's room, so no project takes over or deactivates another's task |
| <a id="mx12"></a>[MX12](README.md#mx12) | Several Machines On One Homeserver | A central homeserver's mode: each machine registers and acts only as the accounts it made, with passwords derived from its own secret, in a room a person made and invites it into; `--machine` on every verb |
| <a id="mx31"></a>[MX31](README.md#mx31) | A Host Named In Upper Case Joins Its Conversation | `--machine` folded to lower case before it is checked, so a host named in upper case joins its project's conversation |
| <a id="mx32"></a>[MX32](README.md#mx32) | The Account Is Cleared With Its Machine Named | The account-level `clear` takes `--machine`, so `porelo vault clear` takes the account's own homeserver away on a machine whose transport is told its name |
| <a id="mx33"></a>[MX33](README.md#mx33) | A Person Is Shown By Their Name | The unit sets Tuwunel's display-name suffix empty, so a person is shown by the name they joined with |
| <a id="mx26"></a>[MX26](README.md#mx26) | Jackson Without Its Known Advisories | jackson-core and jackson-databind 2.22.3 in the transport, past the seven advisories published against 2.22.1 |
| <a id="mx25"></a>[MX25](README.md#mx25) | Publish Without A Downloaded Client | The packages published with `curl` through `.github/scripts/artifactory.sh`, both checksums sent; no JFrog client runs beside the token |
| <a id="mx22"></a>[MX22](README.md#mx22) | A Homeserver Elsewhere Gets Its Registration Token | The setting `registration_token_file`, read by `setup`, `enroll` and `join` and checked by `settings`: absolute, only for a homeserver elsewhere, readable by the account alone |
| <a id="mx21"></a>[MX21](README.md#mx21) | The Transport Keeps Its Tokens From The Account | The running transport not dumpable, so its environment and memory belong to root; the moment before it, measured, is the accepted risk in `doc/decisions.md` |
| <a id="mx18"></a>[MX18](README.md#mx18) | Reformatted To The Shared Style | The shared `.editorconfig` and `spotless:check` in `validate`, the Java reformatted once in `2e795eb`, which `git blame` skips through `.git-blame-ignore-revs` |
| <a id="mx16"></a>[MX16](README.md#mx16) | The Code Read Against Its Skills | All of the code read against seven skills, its findings fixed in `433cebe`, each with its test or why it has none |
| <a id="mx17"></a>[MX17](README.md#mx17) | The Transport Is Renamed Porelo | The transport, its packages, program, settings, directories and accounts say Porelo, installed afresh; a documents test keeps the old name out of every tracked file |
