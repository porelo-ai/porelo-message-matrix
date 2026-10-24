# MX38 — A Room Shows No Helper

**Status:** Accepted.
**Size:** XL - every lifecycle verb, both polls, a registration and a migration; splits into the parts below.

**What must be true.** A person in the project's room sees only the people and the tasks among its members, never
Porelo's administrator or a relay; tasks are enrolled and retired, people joined, and the room read for Porelo as
today. [MX35](README.md#mx35) names the helpers in the meantime.

## Why

- Today the room's members are the person, the tasks, `porelo` (the homeserver's administrator, which makes the room
  and invites every account) and `porelo-relay-<project>` (the account `poll --persons` reads the room with); neither
  helper is a participant a person talks with, and without [MX35](README.md#mx35) a person cannot tell what they are
  and a line mentioning only `porelo` wakes every task (`Person.java` strikes the relay from `to`, not the
  administrator; `porelo` then finds `to` empty).

## Measured

On a test VM, against the pinned Tuwunel:

- The administrator out of the room cannot invite (`403`). With invite power given to the relay first, the relay
  invites and keeps reading, but stays a member.
- No admin command joins past an invite-only room's auth: `users force-join-room` is refused ("cannot join a room
  that is not `public`"). `users list-joined-rooms` lists an account's rooms without the administrator being in
  them.
- A restricted join rule tied to a lobby room the administrator stays in lets a lobby member join the project room
  without an invite only while a member with invite power is still in the project room; with none left the join is
  refused ("cannot join restricted room without `join_authorised_via_users_server`").
- An application service registered through the admin room (`!admin appservices register`) gets the room's events
  pushed without being a member and is not shown. It cannot read the room as itself (`/messages` `403`). But acting
  as an account of its user namespace (`?user_id=`, with only its own token), it invited a second task into the
  room as the first task after the administrator had left, joined as the second, and read the room as it
  (`/messages` `200`). No helper was a member.
- With an application service whose user namespace holds only task accounts (`@porelo-task-.*`, exclusive) and
  whose alias namespace holds `#porelo-.*` (exclusive): acting as a person is refused (`M_EXCLUSIVE`, "User is not
  in namespace"); a person registering a task-like account with the registration token is refused (`M_EXCLUSIVE`);
  a person's account creating a room with a `#porelo-` alias is refused ("Room alias reserved by appservice").
- A task account, acting through the application service, made the project room (room version 12, the creator that
  task, no administrator ever in it), with a restricted join rule tied to a lobby room that holds only the
  administrator and the task accounts, history visible to members (`shared`), and guests forbidden. It invited the
  person, who joined.
- With the task gone and only the person left, a second task joined without an invite: the restricted rule,
  authorized through the person, whose power suffices to invite. It read the person's earlier message both with
  `/messages` and in its first `/sync`.
- A `/sync` position taken acting as one task, used by the next poll acting as another task, handed over the new
  message and repeated none. A task synced with `set_presence=offline` shows as offline.

## The design

- **The account's own homeserver only:** the central homeserver runs with a machine account
  that has no homeserver-administrator rights in a person-created room and keeps its own lifecycle and ownership
  boundaries; this design applies to the account's own local homeserver.
- **Anchored, collision-free namespace per project:** the application service's exclusive user namespace holds only
  that project's task accounts, never a person or the administrator, with an anchored, escaped regex and a
  collision-free project encoding (as `enroll` and `retire` keep projects apart today) so two projects' names never
  overlap and existing human accounts are refused on collision. The exclusive alias namespace holds only the exact
  `#porelo-<project>:<server>` alias, the room namespace is empty, and `sender_localpart` is a separate unprivileged
  account per project that joins no room and holds power in none.
- **Registration and the lobby in `setup`:** `setup` registers the project's application service through the admin
  room with `url: null` so the homeserver queues no pushes, redacting both the secret-bearing registration command
  and its reply as setting a password does today. `setup` also makes the project's lobby: a private room with guests
  forbidden holding only the administrator and the project's task accounts, whose power levels let only the
  administrator invite or change state so tasks cannot invite anyone into the lobby. The service's token takes the
  relay's place among `setup`'s secrets; there is no relay account.
- **The project alias as `conversation`:** `setup` answers the project's alias `#porelo-<project>:<server>` as its
  `conversation` and `room`, stable for the project's life (`porelo` keeps `conversation` opaque in its vault and
  runs `setup` before every `enroll`). The room can therefore be created at the first `enroll` and made anew under
  the same alias when empty without a contract change; `send` and `poll` resolve the alias (today `send` refuses
  one).
- **Room creation, creator power, and `ours`:** the first task enrolled makes the project room acting through the
  application service (room version 12, restricted to members of the lobby, history `shared`, guests forbidden, with
  the project's alias). Because room version 12 gives the creating task irrevocable power, the project's service
  acting as that creator localpart can change room rules, ban, or redact in that project's room and can manage or
  replace the room after that task retires; when no member remains in the room, the next `enroll` makes a fresh room
  under the same alias. `ours` proves a new room by its alias in the exclusive namespace and its creator in the
  project's task namespace, while continuing to accept an existing room created before migration by the
  administrator's provenance proof.
- **Lifecycle verbs and credential revocation:** `enroll` invites the task into the lobby as the administrator, has
  the task join the lobby and then the project room by the restricted rule (authorized through any joined member who
  may invite, task or person), and returns the task's own token for `send`, `poll --direct`, `read`, and `receipt`.
  `join` checks that the room exists and a joined task can admit the person before it creates or changes the
  person's account, then invites the person as that task. `retire` leaves the project room, the lobby, and direct
  chats and invalidates the task's sessions and tokens. `clear` deletes both rooms with `!admin rooms delete`,
  deactivates the accounts, and revokes the service's registration; `restore` rotates the service's token so an old
  token never works after them ([MX24](README.md#mx24)'s promise). `membersElsewhere` keeps protecting invited as
  well as joined members in other projects and does not count the project's own lobby.
- **The room poll bound to the project room alone:** `poll --persons` reads acting as a task in the room with
  `set_presence=offline`, bound to the project room alone so it never reads a task's direct chats or the lobby, with
  a position and a lock of its own kept apart from each task's `--direct` position and stable when the task it reads
  as changes or retires. `Person.deliver` does not strip a task reader from `to`, so a mention of only the reading
  task still addresses that task; once existing rooms are migrated and historical helper references are handled,
  porelo's [B264](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b264) can be proposed for
  rejection.
- **Existing rooms:** their creator stays the administrator, so `ours` keeps accepting such a room by its old proof;
  `setup` makes the lobby, sets the restricted rule, and has the relay and the administrator leave only once a task
  in the project's new namespace is in the lobby and the room as a reader; old task accounts outside the namespace
  keep their direct chats until their task is enrolled again.

## Harm

- **Where the token lives:** `setup` returns the application service's token among its secrets, and the admin room
  redacts the registration command and reply that carry it; Porelo keeps it in its vault under the project and hands
  it to the transport on each run (in its environment today, on its input once [MX23](README.md#mx23) is built),
  never in a file, a command line, a log line, or an unredacted room event. Lifecycle verbs still use the
  administrator's token and `join` logs in as the person to join, so the namespace confinement is specifically that
  of the application service's token.
- **What a leak of the service token could do:** act as every task account of that one project — read the room and
  the tasks' direct chats with people, send unsigned text or forge read receipts as a task, join and leave, and, as
  the room's version-12 creator task, change room rules, ban members, or redact history in that project's room.
  Because only the administrator can invite or change state in the lobby and the namespace is anchored to that
  project, the token cannot admit outsiders through the lobby, act as a person, the administrator, or the
  unprivileged `sender_localpart`, or reach another project. `retire`, `clear`, and `restore` invalidate task
  sessions and rotate or revoke the service token so an old token stops working.
- **What tells a Porelo message from a forged one:** every message between tasks carries the installation's
  signature, which `porelo`'s delivery (`MessageDelivery`) verifies against the allowed peer keys before a task sees
  it, while the filter in `porelo-message-sluice` checks the signature's form only; a holder of the service token
  without the installation's key can send text in Matrix as a task, which tasks ignore, but not a message
  `MessageDelivery` accepts. A person's words carry no signature and are taken by their Matrix sender and Porelo's
  own list of the project's people, which is why people stay outside the namespace.

## What a review found

1. **The room poll exposes a task's direct chat (`Poll.java`):** `poll --persons` processes every joined room of the
   reading account, turning a person's private message to the reading task into a room person file without
   `direct: true`. The room poll must bind to the project room alone, excluding direct chats and the lobby.
2. **Room and direct polls consume each other's unread messages (`Poll.java`):** the `/sync` cursor and lock are
   keyed only by homeserver and account, so a task's `--direct` poll advances the shared cursor past room messages
   and changing the reading task changes the state key, causing replay and separate locks. The room poll must keep
   its own cursor and lock independent of `--direct` and stable across reader changes and retirement.
3. **Tasks could invite into the lobby:** ordinary private-room defaults (`invite: 0`) let a task or a leaked
   service token invite a person or another project's account into the lobby and thus into the restricted room. The
   lobby must enforce administrator-only invitations and state changes, private visibility, and forbidden guests.
4. **The registration boundary is under-specified:** an unescaped project name in `@porelo-task-.*` or `#porelo-.*`
   can overlap another project or collide with a human account, and `sender_localpart` provides an implicit acting
   identity outside `?user_id=`. The registration must use anchored, escaped, collision-free per-project user and
   alias namespaces, an empty room namespace, and a separate unprivileged `sender_localpart`.
5. **Version-12 room creation grants irrevocable admin power to the service:** the first task that creates a
   version-12 room holds permanent creator power that the service can use to change access rules, ban members, or
   redact history, and the service can also forge task read receipts. Harm and the design must state these powers
   and how the room is managed or recovered once that creator task retires.
6. **Migration breaks `ours` and strands legacy tasks (`Lifecycle.java`):** existing rooms have the administrator as
   immutable creator in `m.room.create` and legacy task accounts lie outside the new namespace, so requiring a task
   creator rejects migrated rooms and removing the relay before a new-namespace task is in the room leaves no
   reader. `ours` must keep the administrator-creator proof for migrated rooms, a reader in the namespace must join
   before the relay leaves, and old task accounts must keep their direct chats until enrolled again.
7. **Delayed room creation and replacement break room-ID callers (`doc/transport.md`):** `setup` runs before
   `enroll` and callers do not update `conversation` from `enroll`, while `send` refuses aliases, so neither making
   the room at first `enroll` nor replacing an empty room works if `setup` returns a room ID. `setup` must return
   the stable `#porelo-<project>:<server>` alias as `conversation` and `room`, and `send` and `poll` must resolve it.
8. **`retire`, `clear`, and `restore` leave old credentials working (`Lifecycle.java`, `Accounts.java`, `Keep.java`):**
   leaving two rooms does not invalidate task sessions, deleting rooms does not revoke the application service, and
   restoring a registration preserves the old project token against [MX24](README.md#mx24). Each operation must
   invalidate task sessions and rotate or revoke the service token while keeping separate task tokens for `send`,
   `--direct`, `read`, and `receipt`.
9. **`users list-joined-rooms` loses invitation protection and counts the lobby (`Lifecycle.java`):**
   `membersElsewhere` protects accounts *invited* into another project, which a joined-room listing misses, while
   counting the task's own lobby blocks retirement. `membersElsewhere` must exclude the project's lobby and keep
   protecting both joined and invited members.
10. **Central homeservers cannot run this lifecycle (`Lifecycle.java`):** on a central homeserver the transport uses
    a machine account without homeserver-administrator rights in a person-created room and cannot register services
    or delete rooms via `!admin`. The central homeserver must keep its own lifecycle and ownership boundaries.
11. **Reading as a task strips that task from `to` (`Person.java`):** `Person.deliver` removes `self` from
    recipients, so mentioning only the reading task produces an empty `to`, and historical messages can still
    mention or reply to departed helpers. `Person.deliver` must not strip a task reader from `to` and must handle
    historical helper references before [B264](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b264)
    is rejected.
12. **Harm misidentifies the signature verifier and overstates token isolation (`Poll.java`, `MessageDelivery`,
    `SshSignature.java`):** the filter checks signature format only while `porelo`'s `MessageDelivery` verifies
    allowed peer keys and recorded people, and lifecycle verbs still hold the administrator token and log in as
    people during `join`. Harm must state these boundaries and scope the impersonation claim to the service token.
13. **Deferring `join` after account mutation loses the password (`Lifecycle.java`):** creating an account or
    resetting its password before checking whether a task can admit the person loses the generated password when
    `join` exits `75`. `join` must check that the room and an inviting task are ready before making or changing the
    person's account.
14. **Admin-room registration leaves secrets in the room (`AdminRoom.java`):** `!admin appservices register` sends
    credentials through a persistent room message and reply without redaction. Registration must redact both the
    command and the reply, sanitize failures, and recover if output delivery fails after registration.
15. **An unused callback URL queues outbound pushes on the homeserver:** a registration without a null endpoint
    accumulates queued push events (nine in the probe). The registration must set `url: null` and verify that no
    push queue grows across traffic and restarts.

Found to hold:

- Explicit impersonation outside a disjoint exclusive user namespace is forbidden (`M_EXCLUSIVE`), and authorizing a
  restricted join through a person does not permit sending as that person.
- Ordinary room members already hold invite power under today's defaults (`invite` 0), so restricted-join authorization
  adds no new room privilege, though a user namespace does not stop another room from inviting a task.
- Restricted joining (`m.room.join_rules: restricted`) requires a joined member with invite power in the project
  room, matching the measurement.
- Polling does not post read receipts; `read` runs later as the task that took the message.

## How it splits

Each part becomes its own issue when this one is taken up:

1. **Part 1 — The room's poll reads only the project room, with its own position** (M): independent of the rest,
   worth having on its own; the room poll bound to the room, its position and lock apart from `--direct`, stable
   across the account it reads as.
2. **Part 2 — A project's application service** (L): registration with an exact namespace and a separate sender, task
   accounts' prefix with the project, redacted registration, `url: null`, token rotation and revocation, backup and
   restore.
3. **Part 3 — The lobby and a room no helper is in** (L): the lobby's power levels, the restricted room made at the
   first `enroll`, the creator's power and the room's recovery, the alias as `conversation`, `send` and `poll`
   resolving it, `join`'s order.
4. **Part 4 — Lifecycle and migration without helpers** (L): `enroll`, `join`, `retire`, `clear` through the service,
   `membersElsewhere` with invitations, the migration of existing rooms, the central homeserver left as it is; then
   the relay goes and porelo's [B264](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b264) can
   be proposed for rejection.

Order: 1, then 2, 3, 4. Build estimate for all: 960 min, a guess.

## Open questions

1. A person joining while no task is in the room (none enrolled yet, or all retired): the room takes a person only
   from a task in it. Proposed: `join` then answers 75 ("the conversation takes a person once a task runs") and
   Porelo tries again after the next enroll.
2. A room whose last member left (no person, the last task retired) cannot be entered by anyone. Proposed: the next
   `enroll` makes a new room under the same alias; the old one's history is no longer shown.

## To be checked

- The task accounts' prefix and how its anchored, escaped form keeps two projects apart and avoids human accounts,
  read against the check that keeps projects' task accounts apart and what Porelo passes as `--task`.
- That a backup and restore ([MX24](README.md#mx24)) carries the registration and the lobby and rotates the
  application service's token so the old token is refused.
- Whether Tuwunel accepts a registration with `url: null` so it pushes to nothing and queues no pushes across
  sustained traffic and restart.
- How a client shows the room's version-12 creator once that task's account is deactivated, and whether the
  application service can still act as that creator localpart to manage or replace the room after `retire`.
- Whether the room poll's independent `/sync` cursor and lock hold across both polling orders (`--persons` and
  `--direct`), concurrent polls, reader retirement, and backlogs exceeding one page.
- How `!admin appservices` unregisters or rotates a registration on `clear` and `restore`, and that redacting the
  registration command and reply leaves no secret in served admin-room events or logs.
- How `membersElsewhere` reads invited as well as joined memberships across projects once the administrator is no
  longer a member of every project room.
