# MX81 — A Coordinator Off The Host Reaches The Task's Room

**Status:** Backlog. Bug, found by the operator on 2026-11-06 in a rehearsal of the move.
**Size:** S - the start's steps, their check on a host, and the documents; [MX76](README.md#mx76)'s code is there.

**What must be true.** A task started on a host for a project whose agents report to a coordinator on the development
machine talks in that coordinator's room from its first step. No room on the host stands in for it, and nobody relays
by hand. When the tunnel is not up, the start stops before the task is made and says why. It does not start a task
that reports into a room nobody reads.

## What happened

The rehearsal started a task for a project whose settings say `mail: transports: matrix: {}`. On a host that takes the
host's homeserver behind its gate, as it should. The task's agent reported "in the room to Agent Coordinator", but
that room was the host's. The coordinator, on the development machine without a login on the host, cannot read it:
- the gate admits only a person's own host login;
- the room tools know only the development machine's homeserver.

So the operator relayed in the window. [MX76](README.md#mx76)'s tunnel is the way meant for this, but it was not set up before the
start.

## Plan

- **The start of a moved project follows [MX76](README.md#mx76)'s way** (doc/homeserver.md, "A host reaching the room on the development
  machine"). The operator's steps below are written into the move's runbook, [MX43](README.md#mx43)'s steps, before the task starts.
- **This repository's part:** check that `setup` through the tunnel answers 75 while the tunnel is down, and 78 naming
  the token while none is lent. Then the operator's start stops instead of making a task that cannot report. If the
  start in porelo goes on regardless, that is filed in porelo.
- **Red first:** none in code. The steps' check is the run on the host, with [MX76](README.md#mx76)'s To be checked points.

## The operator's steps for the next pass (Do / See)

1. **Do:** on the development machine, read the homeserver's loopback port: `PORELO_MATRIX_PORT` in this account's
   `homeserver.conf`.
   **See:** a port; `curl -s http://127.0.0.1:<port>/_matrix/client/versions` answers.
2. **Do:** `build/room-tunnel.sh <login on the host> <host> <port>` from porelo-host, left running in its own terminal.
   **See:** it prints `unix:/run/porelo-host/people/<login>/room.sock` and stays open. On the host, `ls -l` of that path
   shows a socket owned by `<login>`, mode 0600. A second account on the host cannot open it.
3. **Do:** in the project's settings, replace `matrix: {}` with `matrix: {homeserver: "unix:/run/porelo-host/people/<login>/room.sock",
   room: "<the room's alias on the development machine>"}`, and sign them as usual.
   **See:** `porelo-message-transport-matrix settings` on the host accepts them.
4. **Do:** lend this homeserver's registration token from the operator's vault, under the entry Porelo names for that
   socket.
   **See:** without it, the first `setup` stops with 78 naming `PORELO_MATRIX_REGISTRATION_TOKEN`; with it, `setup` prints
   `"admitted": false`.
5. **Do:** from this room, a member invites `@porelo.<login>:localhost`.
   **See:** the next `setup` prints `"admitted": true`.
6. **Do:** lay the local agents' signed certificates: `build/lay-certificates.sh <login> <host> <project> <files>...`.
   **See:** it names each fingerprint and `porelo task certificate` finds each one.
7. **Do:** start the task.
   **See:**
   - The task's first report arrives in this room, from `@<task>.<login>`, and `porelo-room-read` shows it.
   - A line posted here reaches the task as an agent's message.
   - Stopping the tunnel makes the task's next send answer 75, not a post into another room.

## To be checked

- Each of [MX76](README.md#mx76)'s To be checked points, run in the steps above on a host.
- Whether porelo's start goes on after the transport's `setup` answers 75 or 78, or stops before making the task.
