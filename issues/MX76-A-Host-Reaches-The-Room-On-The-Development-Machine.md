# MX76 — A Host Reaches The Room On The Development Machine

**Status:** Idea.

**What must be true.** While agents move into tasks, a task on a rented host talks in the project's room on the
development machine's homeserver, over one `ssh -R` the development machine opens to the host, to one socket only.
Nothing else of that machine can be reached from the host, no other account on the host can open the socket, and the
room and the work on the development machine go on when the host is gone.

## Why

The host is rented for a few hours at a time, so the room stays on the development machine until the last agent has
moved. The way there over the VPN is gone, with its published homeserver, certificates and machine tokens.

## The shape

- **The development machine opens it:** `ssh -R <socket on the host>:127.0.0.1:<port>`, to the homeserver's loopback
  port, which speaks plain HTTP on a tunnel that encrypts. It is opened anew for each new host.
- **The host's side is mostly there:** the transport takes `homeserver: unix:<path>` and answers 75 while the socket is
  unreachable; the host's SSH policy lets a person bind a remote socket and refuses every remote TCP listener.
- **What stands in the way:** `Settings.hosted` takes every `unix:` homeserver for a host's gate and asks no registration
  token, while the development machine's homeserver registers only with one. The two must be told apart.
- **Accounts as on a central homeserver:** `@porelo.<login>`, its relay and its tasks, with the token lent from the
  person's vault; a member of the room invites `@porelo.<login>`, and `setup` says "not admitted" until then.
- **The filter's side needs nothing:** it reads files, knows no transport and opens no socket, and what leaves a moved
  task passes it before the transport, as today. Its directory contract does not change.
- **A local agent's line reaches a moved task as an agent's message, through the filter, never as a person's words.**
  This protects no more than work on the development machine does today: full security holds only once an agent has
  moved into a task. So `Login.person` keeps refusing every `@porelo-…`, and `poll --persons` keeps skipping such a line.
- **What that takes:** a line in the transport's message form, `org.fuin.porelo.message` beside
  `org.fuin.porelo.signature`, which `poll` hands over as a file. The room client on the development machine posts plain
  text today. The message is `ROLE_AGENT` with a `metadata.from` that is a plain name such as `porelo-agent-core`, since
  the filter refuses a Matrix id as `ENVELOPE/PEER_NAME_MALFORMED` and checks only a signature's shape. `MessageDelivery`
  holds a message without a signature, or one whose key traces to no certificate.
- **Each local agent has a key of its own and a certificate on it.** `MessageDelivery` wants, in order: a `<message>.sig`
  beside the message by an Ed25519 key, in the namespace of the message intake, over the message's bytes; a certificate
  the receiver finds by that key's fingerprint, with one principal, `task:<project>:<name>`; its signer among the
  project's certifiers, so a person's key signs it; `metadata.from` that `<name>`, the whole principal, or absent; then
  the inbound filter in blocking mode, with no filter installed the message being held.
- **A small script lays the certificate into the moved task's account when a host comes up.** The receiver looks in its
  account's own record, one JSON file per fingerprint, of which it needs only the `certificate` field, and that line
  proves itself by its own signature. So Porelo does not change, and no code of this stays in it after the move.
- **The person's signature stays a deliberate step:** the script lays what a person signed and signs nothing itself.
- **Local agents have names no task has,** so no real task shares a peer name with one, and they stay listed as external:
  a peer the project does not list or lists as external passes the filter, while one listed otherwise would count against
  `per_day`.
- **What it is not:** no second way into the homeserver for anything but the transport on the host, and no homeserver on
  the host for this project until the last agent has moved.

## To be checked

- The socket's path: a project's signed settings name one path, while a runtime directory differs per account.
- The mode `ssh` gives the socket on the host, read there, and that a second account is refused.
- What keeps the tunnel open on the development machine and who starts it, and what a task sees while it is down.
- Where the script lives, the record's directory in the moved task's account and its file's name, and what renews a
  certificate on a host that lasts past its validity: the issuer caps it at a week, the receiver checks it at arrival.
- The account names and the room's alias on a homeserver whose server name is `localhost`, read from the host.
