# Issues

The index, grouped by state; within a group the order is the priority. The number is identity, not
sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx94"></a>[MX94](MX94-The-Homeserver-Holds-Its-Limits-On-Any-Machine.md) | S | - | The local homeserver's container sets its open files too, and a test runs the real homeserver under a process limit its uncapped pool would break |
| <a id="mx88"></a>[MX88](MX88-A-Head-Names-A-Task-By-Its-Whole-Display.md) | S | - | A head `Agent Pi:` names only the task shown `Agent Pi`, and `Agent Pi (fix):` only the one shown so |
| <a id="mx87"></a>[MX87](MX87-A-Persons-Room-Mention-Is-For-All-Agents.md) | S | - | A person's line that mentions the whole room (`@room`) is marked `"all": true`, as one headed `All agents:` |
| <a id="mx86"></a>[MX86](MX86-A-Persons-Line-For-All-Agents-Says-So.md) | S | [MX85](README.md#mx85) | A person's line headed `All agents: …` is marked `"all": true` in its person file, so Porelo hands it to every task of the project |
| <a id="mx85"></a>[MX85](MX85-A-Persons-Line-Headed-By-A-Task-Names-It.md) | S | - | A person's line headed `Agent Pi: …`, typed with no pill, is meant for the member shown as Agent Pi, so Porelo wakes that task |
| <a id="mx84"></a>[MX84](MX84-Setup-Waits-For-No-Poll-It-Need-Not.md) | S | - | Several tasks of one project started at once all get through `setup`: it takes the relay's poll lock only when it must forget the relay's position |
| <a id="mx81"></a>[MX81](MX81-A-Coordinator-Off-The-Host-Reaches-The-Tasks-Room.md) | S | - | A task on a host reports in the room of a coordinator on the development machine from its first step, over [MX76](README.md#mx76)'s tunnel set up before the start; without the tunnel the start stops instead of reporting into a room nobody reads |
| <a id="mx51"></a>[MX51](MX51-Long-Verbs-Say-How-Far-They-Are.md) | S | - | `setup`, `backup` and `restore` say on stderr what they wait for and for how long, every 10 s; waits for porelo to show a transport's stderr as it comes |
| <a id="mx52"></a>[MX52](MX52-A-Person-Joins-Another-Projects-Room-Unchanged.md) | S | - | A person with an account for one project joins another project's room on the same homeserver by an invitation, keeping their password and sessions |
| <a id="mx80"></a>[MX80](MX80-The-Workflows-Run-On-A-Forge-As-On-GitHub.md) | S | - | The workflows run unchanged and green on the host's Forgejo and on GitHub: publication and snapshot registry switched by the setup's variables, the homeserver in the forge's job, `porelo.yml` in the keys Porelo reads |
| <a id="mx79"></a>[MX79](MX79-Only-The-Forwards-Own-Page-Reads-The-Gate.md) | S | - | A page not served at the forward the request came in on reads no answer of the gate's and registers or signs in nothing, and no page elsewhere starts the person's sign-in |
| <a id="mx39"></a>[MX39](MX39-A-Backup-Is-Its-Own.md) | S | - | A copy `backup` reports as made holds the database and positions of that one backup: no late earlier snapshot taken for it, and no backup emptying a directory another one filled |
| <a id="mx76"></a>[MX76](MX76-A-Host-Reaches-The-Room-On-The-Development-Machine.md) | M | - | A task on a rented host talks in the room on the development machine's homeserver, over one `ssh -R` to one socket only, while agents move |
| <a id="mx68"></a>[MX68](MX68-The-Gates-Refusals-Are-Held-By-Tests.md) | M | - | Whoever removes a refusal of the gate's for a login, a registration or a reserved alias sees a unit test fail |
| <a id="mx66"></a>[MX66](MX66-The-Default-Project-Has-No-Room.md) | S | - | The transport refuses to make or join a room for the project `default`, whatever settings reach it; what is left over can still be cleared |
| <a id="mx24"></a>[MX24](MX24-The-Room-Outlives-The-Machine.md) | M | - | `backup <dir>` and `restore <dir>` for the host's playbook, so the local homeserver's conversation outlives a rented host; `setup` takes `@porelo` back after a restore |
| <a id="mx36"></a>[MX36](MX36-A-Backup-Runs-While-Tasks-Poll.md) | S | - | `backup <dir>` succeeds within its bound while Porelo's poll loops run, instead of starving behind overlapping shared holds of `keep.lock` |
| <a id="mx23"></a>[MX23](MX23-The-Transport-Takes-Its-Tokens-On-Its-Input.md) | M | - | The transport's tokens never in its environment: Porelo hands them on the transport's input, so no moment at its start leaves them readable |
| <a id="mx75"></a>[MX75](MX75-A-Stalled-Client-Is-Closed.md) | - | - | A client that stalls halfway through a request loses its connection to the gate at the idle limit, and a test fails if that stops |
| <a id="mx69"></a>[MX69](MX69-A-Lost-Message-Or-Copy-Fails-A-Test.md) | M | - | A silently dropped message, an unfilled sync gap, an unrecorded sender or a hollow restore fails a unit test |
| <a id="mx70"></a>[MX70](MX70-The-Gates-Rules-Are-Held-To-Their-Edges.md) | S | - | Moving an edge of who belongs to a login, or of which redirect is the person's own client, fails a unit test |
| <a id="mx71"></a>[MX71](MX71-The-Gates-Request-Parser-Is-Held-To-Its-Refusals.md) | S | - | A request that could read differently to the gate and the homeserver fails a unit test if it ever passes |
| <a id="mx72"></a>[MX72](MX72-A-Token-Is-Random-And-An-Admin-Command-Takes-One-Argument.md) | S | - | A homeserver token not random, an admin command's user id unchecked or a credential over its limit fails a unit test |
| <a id="mx73"></a>[MX73](MX73-Every-Trust-Path-Checks-The-Certificate.md) | S | - | Each `checkServerTrusted` overload refuses an unknown chain, and a file with several CAs trusts them all, under test |
| <a id="mx74"></a>[MX74](MX74-Small-Refusals-Are-Held-By-Tests.md) | S | - | The smaller refusals, each cheap to hold, fail a unit test if removed |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx89"></a>[MX89](MX89-A-Test-Person-Signs-In-Through-The-Gate-Unattended.md) | S | - | A rehearsal signs the test person into the project's room through the gate's SSO without a browser, posts, reads and logs out; no login a real host does not offer |
| <a id="mx90"></a>[MX90](MX90-The-Rooms-Scenarios-Are-Rehearsed-On-A-Host.md) | S | [MX89](README.md#mx89) | A rehearsal proves on a host that a person's line reaches exactly the tasks meant (a head, `All agents:`, `@room`, nobody, a direct chat) and that the task's answer comes back |
| <a id="mx91"></a>[MX91](MX91-The-Rehearsal-Says-A-Wake-Was-Answered.md) | XS | [MX89](README.md#mx89) | The rehearsal tells the move check a wake was answered only after it saw the task's answer in the room |
| <a id="mx95"></a>[MX95](MX95-The-Transport-Says-Who-Is-In-The-Room.md) | M | - | `members --project P` tells Porelo who is joined or invited in a project's room, each with the name the room shows and whether a task, a person or a helper, so the window can say who is in |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx43"></a>[MX43](MX43-This-Repository-Moves-Into-A-Porelo-Task.md) | S | [MX80](README.md#mx80) | This repository worked by its own agent inside a Porelo task on the rented host, its issue to Review there, the forge's build green, reached in the room by its name; a trial, then the way back |
| <a id="mx60"></a>[MX60](MX60-The-Gate-Says-Why-It-Failed.md) | S | - | The host's gate logs why it answered 502 or could not act as the administrator (token missing or refused, homeserver silent, an unexpected status of the administrator's API), never a token |
| <a id="mx29"></a>[MX29](MX29-The-Homeserver-On-A-Socket-Of-The-Accounts-Own.md) | - | - | The account's own homeserver behind a unix socket only it can open, across this transport, porelo and porelo-frontend, closing the window the check of the listener's owner leaves |
| <a id="mx96"></a>[MX96](MX96-A-Person-Shows-Under-The-Name-They-Chose.md) | M | - | On a host a person shows in the room under the name the host passes with `keep-room`, set when their account has none, never over a name of their own |

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="mx82"></a>[MX82](MX82-A-Local-Agents-Line-Reaches-A-Moved-Task.md) | S | - | 2 | A local agent's line reaches a moved task as its signed message, believed through a certificate a person signed; `poll` already carries it, a test holds that and the room client signs |
| <a id="mx77"></a>[MX77](MX77-The-Rooms-Poll-Has-A-Position-Of-Its-Own.md) | M | - | 0 | `poll --persons` reads one project's room only, with a position and a lock of that room, apart from `--direct` and stable when the reading account changes |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx93"></a>[MX93](MX93-The-Tests-Follow-The-Pyramid.md) | L | - | Every flow proven by unit tests over fakes and the unit suite under a minute: today 57 % in-process unit tests, 31 % process tests in the unit suite (178 s), 12 % against the real homeserver; the lifecycle, room, direct and receipt paths only there |
| <a id="mx38"></a>[MX38](MX38-A-Room-Shows-No-Helper.md) | XL | - | A person sees no helper account in the room, on the account's own homeserver and on a host: a project's application service acts through its task accounts only, a lobby the people never see admits them, and neither the administrator nor a relay is a member; splits into parts of L or smaller |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mx97"></a>[MX97](MX97-A-Verb-Refuses-A-Repeated-Flag-And-Repeats-No-Secret.md) | - | - | A lifecycle verb refuses a flag given twice with 64, and no failure line repeats a secret or a control character from a homeserver's error text |
| <a id="mx83"></a>[MX83](MX83-The-Homeservers-Data-Survives-A-Volume-Prune.md) | - | - | The homeserver's accounts, rooms and messages survive a routine `podman volume prune` while it is stopped, and a machine that keeps its homeserver writes a recent copy without being asked |
| <a id="mx78"></a>[MX78](MX78-A-Backup-Knows-Its-Own-Snapshot.md) | - | - | A copy holds all its positions name however many earlier snapshots still run, and a snapshot finishing during the copy does not fail it for good |
| <a id="mx64"></a>[MX64](MX64-No-Port-Reaches-The-Homeserver-Behind-Its-Gate.md) | - | - | In gate mode the homeserver's unit publishes no TCP port, so `127.0.0.1:8008` accepts no connection and only the gate's socket leads in |
| <a id="mx63"></a>[MX63](MX63-A-Loopback-Client-Signs-In-On-The-Persons-Click.md) | - | - | Before the gate hands an SSO login token to a client's own loopback port, such as nheko's, the person confirms it on a page of the gate's; without that click no token goes there |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="mx65"></a>[MX65](README.md#mx65) | The Host Keeps A Project's Room | Behind the gate, the host's helper makes a project's room and keeps it to the persons the host names with `keep-room`, as the administrator, and `drop-room` deletes it when the host stops carrying the project; tested in sprint 6q: passed |
| <a id="mx67"></a>[MX67](README.md#mx67) | The Registration Token Comes Only With The Secrets | A homeserver elsewhere's registration token reaches the transport only among the secrets on its input, and the setting `registration_token_file` is gone; tested in sprint 6q: passed |
| <a id="mx56"></a>[MX56](README.md#mx56) | The Homeserver Knows The Person At Its Gate | Every Matrix client on a host reaches the homeserver only through one Unix socket, whose gate takes the caller's host login as who they are, binds every request to that login's accounts and alone registers accounts under it; tested in sprint 6m: passed |
| <a id="mx61"></a>[MX61](README.md#mx61) | The Login Token Goes Only To The Homeserver's Own Clients | The gate's SSO redirect hands a login token only to the homeserver's own clients, Element Desktop by its scheme or a web client at the person's forward, and refuses any other URL; tested in sprint 6m: passed |
| <a id="mx62"></a>[MX62](README.md#mx62) | Expel Reaches The Homeserver Behind Its Gate | `expel` reaches the homeserver behind the host's gate through its private socket with the gate's administrator token, so a purged login's accounts are deactivated there too; tested in sprint 6m: passed |
| <a id="mx58"></a>[MX58](README.md#mx58) | The Transport Reaches The Homeserver On A Socket | The transport reaches a homeserver on a Unix socket (`homeserver: unix:…`) and takes a `*.localhost` name as loopback, with no VPN or certificate; tested in sprint 6m: passed |
| <a id="mx30"></a>[MX30](README.md#mx30) | Only A Person Speaks As A Person | On a central homeserver a task takes words as a person's only from a person's own account, `@<login>`, so a person's direct chat with a task is their own channel; tested in sprint 6m: passed |
| <a id="mx57"></a>[MX57](README.md#mx57) | A Person's Accounts Are Named By Their Login | A person's accounts are named by their login, not a machine: `@<login>`, `@porelo.<login>`, relays and tasks by login, `members --persons` and `expel <login>`; tested in sprint 6m: passed |
| <a id="mx59"></a>[MX59](README.md#mx59) | The VPN Path Goes | `publish`, the account's own CA and certificates, `ca_certificate`, the machine tokens and `admit`/`dismiss` removed with their tests and docs; tested in sprint 6m: passed |
| <a id="mx44"></a>[MX44](README.md#mx44) | The Host's Homeserver Gives Each Person A Token | `machine-token issue`, `revoke` and `expel` give each person's machine on a prepared host's shared homeserver a registration token of its own and take it and every account of that machine away, with no project or room |
| <a id="mx54"></a>[MX54](README.md#mx54) | The Homeserver Serves A Certificate Made Elsewhere | A published homeserver serves a server certificate and key the host's administrator made elsewhere, signed by a CA whose key never reaches the machine, and makes no certificate of its own beside them |
| <a id="mx41"></a>[MX41](README.md#mx41) | The Agents Talk In The Project's Room | `room read`, `post` and `wait` let an agent on the machine read, post to and wait on the project's room as a person, with its login read from porelo's vault over a pipe, and `join --token` makes such an account |
| <a id="mx42"></a>[MX42](README.md#mx42) | The Host's Tasks Reach The Room Over The VPN | `publish` serves the account's own homeserver over `https` on the machine's VPN address with a certificate of its own, and `admit`/`dismiss` give another machine a registration token of its own and a place in the project's room |
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

## Rejected

| # | Title | Why |
|---|---|---|
| <a id="mx92"></a>[MX92](README.md#mx92) | Element Web Signs In Headless As A Smoke | The gate answers a browser page only from its own origin or Element Desktop's, so Element web cannot sign in there unless the host is widened; the scripted client already proves the same path, and Element's look stays in the operator's own test |
| <a id="mx45"></a>[MX45](README.md#mx45) | A Project Admin Brings The Room Onto A Central Homeserver | Replaced by [MX65](README.md#mx65): the host's helper makes and keeps the room with `keep-room` and `drop-room`, and `setup --create-room` is gone |
| <a id="mx55"></a>[MX55](README.md#mx55) | A Person Has Their Own Account In The Room | Replaced by the one-key design; a person's own account `@<login>` and `members --persons` are part of [MX57](README.md#mx57) |
| <a id="mx53"></a>[MX53](README.md#mx53) | A Person Added Again Keeps Their Name | Replaced by the one-key design; a purged login is never made again on a host, so a person comes back under a new login ([MX57](README.md#mx57)) |
