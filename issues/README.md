# Issues

The index, grouped by state; within a group the order is the priority. The number is identity, not
sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx24"></a>[MX24](MX24-The-Room-Outlives-The-Machine.md) | M | - | `backup <dir>` and `restore <dir>` for the host's playbook, so the local homeserver's conversation outlives a rented host, as [PJ45](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj45) plans; `setup` takes `@porelo` back after a restore |
| <a id="mx36"></a>[MX36](MX36-A-Backup-Runs-While-Tasks-Poll.md) | S | - | `backup <dir>` succeeds within its bound while Porelo's poll loops run, instead of starving behind overlapping shared holds of `keep.lock` |
| <a id="mx41"></a>[MX41](MX41-The-Agents-Talk-In-The-Projects-Room.md) | M | - | The agents on this machine read, post to and wait on the project's room as people, `room read`, `post` and `wait` with the login read from porelo's vault over a pipe, replacing the channel file before the first move of [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59) |
| <a id="mx42"></a>[MX42](MX42-The-Hosts-Tasks-Reach-The-Room-Over-The-VPN.md) | L | - | The account's own homeserver also served over `https` on this machine's VPN address, with a certificate of its own, so the rented host's tasks talk in the same room |
| <a id="mx23"></a>[MX23](MX23-The-Transport-Takes-Its-Tokens-On-Its-Input.md) | M | - | The transport's tokens never in its environment: Porelo hands them on the transport's input, so no moment at its start leaves them readable |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx52"></a>[MX52](MX52-A-Person-Joins-Another-Projects-Room-Unchanged.md) | S | - | A person with an account for one project joins another project's room on the same homeserver by an invitation, keeping their password and sessions (walk 21) |
| <a id="mx51"></a>[MX51](MX51-Long-Verbs-Say-How-Far-They-Are.md) | S | - | `setup`, `publish`, `backup` and `restore` say on stderr what they wait for and for how long, every 10 s; waits for porelo to show a transport's stderr as it comes |
| <a id="mx29"></a>[MX29](MX29-The-Homeserver-On-A-Socket-Of-The-Accounts-Own.md) | - | - | After [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39). The account's own homeserver behind a unix socket only it can open, across this transport, porelo and porelo-frontend, closing the window the check of the listener's owner leaves |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx30"></a>[MX30](MX30-Only-A-Person-Speaks-As-A-Person.md) | M | - | After [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39). On a central homeserver a task takes words as a person's only from the people the project names, so the operator's direct chat with a task is the operator's own channel and no other machine's account speaks in it |
| <a id="mx38"></a>[MX38](MX38-A-Room-Shows-No-Helper.md) | XL | - | A person sees no helper account in the room: a project's application service acts through its task accounts only, a lobby the people never see admits them, and neither the administrator nor a relay is a member; splits into parts of L or smaller |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx43"></a>[MX43](MX43-This-Repository-Moves-Into-A-Porelo-Task.md) | - | - | This repository worked by its own agent inside a Porelo task on the rented host, its issue to Review there, the forge's build green, reached in the room by its name |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx44"></a>[MX44](MX44-The-Hosts-Homeserver-Gives-Each-Person-A-Token.md) | M | - | For [PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78), estimated by the operator. A prepared host's shared homeserver gives each person a registration token of their own and takes it away (`machine-token issue`, `revoke`, `expel`), with no project or room |
| <a id="mx45"></a>[MX45](MX45-A-Project-Admin-Brings-The-Room-Onto-A-Central-Homeserver.md) | M | porelo's [B312](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b312) | For [PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78), estimated by the operator. On a central homeserver the project admin's Porelo makes the project's room (`setup --create-room`) and keeps it to the signed developer list (`members`) |
| <a id="mx39"></a>[MX39](MX39-A-Backup-Is-Its-Own.md) | S | - | A copy `backup` reports as made holds the database and positions of that one backup: no late earlier snapshot taken for it, and no backup emptying a directory another one filled |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="mx46"></a>[MX46](README.md#mx46) | A Machine's Token Holds At Once | The registration tokens moved into a `tokens` directory the homeserver's unit mounts, so a machine admitted or dismissed while it runs is taken or refused at once; an earlier token file moves there on the next start |
| <a id="mx47"></a>[MX47](README.md#mx47) | A Central Setup Joins When Already Invited | A machine invited before it registered, as `admit` does, joins at its first `setup`; a first setup that cannot be admitted keeps the secret it made and answers not admitted |
| <a id="mx48"></a>[MX48](README.md#mx48) | Not Yet Admitted Names Its Account And Room | A central `setup` not yet admitted answers `address` and `room`, so Porelo names the account to let in and the room |
| <a id="mx49"></a>[MX49](README.md#mx49) | Clear On A Central Homeserver Says What Stopped It | `clear` on a central homeserver ends 75 when the homeserver is not reached or busy, and 0 when it no longer knows the machine's token, saying so |
| <a id="mx50"></a>[MX50](README.md#mx50) | Setup Takes The Port The Homeserver Runs On | A first `setup` stops a homeserver that already runs before choosing the port, and one an earlier version started, so it does not wait on a port nothing listens on |
| <a id="mx40"></a>[MX40](README.md#mx40) | A Person Leaves A Project's Room | `part` takes a person out of a project's room and locks the account out of every session unless another project's room keeps it, and `join` takes such an account back without `--reset` |
| <a id="mx34"></a>[MX34](README.md#mx34) | The Homeserver Starts On A Large Machine | The local homeserver's container bounded to 512 processes, 1 GiB and 2 CPUs, with Tuwunel's database pool and caches capped, so it starts and backs up on a machine of any size |
| <a id="mx35"></a>[MX35](README.md#mx35) | A Person Knows The Room's Helpers | The administrator and the relay carry display names a person reads, and a line naming only them is marked `helpers` for Porelo to answer, waking no task |
| <a id="mx37"></a>[MX37](README.md#mx37) | The Homeserver Says Why It Did Not Start | A `setup` that gives up waiting prints the homeserver's own last error, never a secret, and `describe` says what a start waits on |
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
