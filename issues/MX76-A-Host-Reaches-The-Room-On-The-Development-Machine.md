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
- **A local agent's line does not reach a moved task today.** On a central homeserver `Person.deliver` hands over only
  a person's own account, and `Login.person` refuses every `@porelo-…`, so `poll --persons` skips such a line with a
  report. Read in the code, not run.
- **What it is not:** no second way into the homeserver for anything but the transport on the host, and no homeserver on
  the host for this project until the last agent has moved.

## Open questions

1. How a local agent's line reaches a moved task. As a person's words it needs an account `Login.person` takes, and
   then reaches the task unfiltered, since a person's words are not filtered on the way in. As a message file for the
   filter it is `ROLE_AGENT` with a `metadata.from` that is a plain name such as `porelo-agent-core`, since a Matrix id
   is refused as `ENVELOPE/PEER_NAME_MALFORMED`; a signature is optional to the filter, which checks only its shape.

## To be checked

- The socket's path: a project's signed settings name one path, while a runtime directory differs per account.
- The mode `ssh` gives the socket on the host, read there, and that a second account is refused.
- What keeps the tunnel open on the development machine and who starts it, and what a task sees while it is down.
- The account names and the room's alias on a homeserver whose server name is `localhost`, read from the host.
