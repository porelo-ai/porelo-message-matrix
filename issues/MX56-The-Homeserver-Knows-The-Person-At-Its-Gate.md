# MX56 — The Homeserver Knows The Person At Its Gate

**Status:** Accepted.

**What must be true.** On a host's homeserver (not on a homeserver of one account's own on a person's machine), every Matrix client reaches the host's homeserver only through one Unix socket, and
the homeserver knows who is calling from the caller's own login on the host: a person or task acts only as an
account of their own login, anybody else is refused at the first request, and a locked login gets no further request
through.

## Why

Today another machine reaches an account's homeserver over a VPN address with TLS, and joins it with a registration
token per machine. Without the VPN, every person reaches the host by ssh and has an account there, so the host's own
login says who calls. A TCP port on the host's loopback can be opened by every account on the host, and taken by any
of them while the homeserver is down, to catch the tokens of everybody's forwards; a socket in a directory only
admitted logins can open leaves neither gap.

## The shape

The contract, in words:

- **The socket.** `/run/porelo-matrix/client.sock`, made by a system socket unit the host installs, owned by the
  homeserver's account, group `porelo-people`, mode 0660, in a directory of the same owner and group, mode 0750. The
  host keeps the group equal to its admitted, unlocked logins, and never puts the homeserver's own account in it.
- **The gate.** `porelo-message-transport-matrix gate`, the service that socket starts, runs as the homeserver's
  account and passes the Matrix client API on to the homeserver.
- **Nothing else reaches the homeserver.** The host's homeserver listens where only its own account can connect: not
  on a TCP port another account of the host can open or take. The gate never sends a token to a listener it has not
  proven its own. A task's sandbox never reaches the socket: a task speaks only through Porelo, whose transport runs
  outside it.
- **Before the first registration,** the host's preparation registers the homeserver's administrator `@porelo` and
  keeps its token in the homeserver's account, as the first machine token's issue does today; the gate refuses every
  registration until then, so no caller can become the administrator.
- **Who calls.** The gate reads the caller's uid from the socket (`SO_PEERCRED`) at each connection and takes that
  user's name as the login, and reads the group anew at each request. A uid whose user is not in `porelo-people`, or
  whose name is no login (see below), is refused before anything reaches the homeserver. A person's forward
  (`ssh -L <port>:/run/porelo-matrix/client.sock`) arrives as that person, since sshd connects as them.
- **Every request is bound to the caller's login, and what cannot be bound is refused.** An account belongs to a
  login when its localpart is the login itself, or `<name>.<login>` with a `<name>` the transport takes for a task.
  The gate passes the client API only: a request with an access token (in the header or the query) only when the
  token's account is the caller's login's, asked of the homeserver and kept, by the token's hash, in a bounded cache;
  a sign-in or registration only for an account of the caller's login; and without a token only the few requests a
  client needs before it signs in. The homeserver's administrator is reached by no caller; the gate alone acts as it.
- **A login** begins with a lower-case letter, holds lower-case letters, digits, `-` and `_` only, has no dot, is at
  most 32 characters, and is none of the names the transport keeps for itself (`porelo` and every `porelo-…`), so no
  account of one login ends as another's. A user of the host whose name is no login is refused.
- **Room aliases** beginning `#porelo-` are made only by the gate's own rule: `#porelo-<project>` only for
  `@porelo.<login>` of a login the host names as that project's admin, in a file the host keeps beside the socket.
  Every other caller is refused, so nobody takes a project's room name before its admin.
- **Registration.** The homeserver takes registrations only with a registration token the gate alone can read. A
  client registers as Matrix clients do: for a username of the caller's login, the gate answers the token stage
  itself, so the password goes nowhere but the homeserver's password store; a taken name, a name of another login,
  an expelled login's name and anything that is no localpart are refused.
- **A lock** holds at the locked login's next request; a sync it held open before ends at its own timeout and is
  not renewed.

## Acceptance

- Over a real `ssh -L` to the socket on a test machine, a person's Matrix client registers `@<login>`, signs in,
  syncs (a sync held open for its whole timeout included) and sends in a room.
- A uid outside `porelo-people` is refused at its first request; a login dropped from the group is refused at its
  next request on a connection already open, with no restart. Seen to fail: such a request passes.
- The gate registers `@<login>` and `@<name>.<login>` for that login. Seen to fail: it registers
  `@<name>.<other login>`, `@.<login>`, a bare other login, a taken name, the administrator's name, or a localpart
  with a space or a newline; any caller registers before the administrator exists; a caller other than a project's
  admin names a room `#porelo-<project>`.
- Seen to fail: a sign-in, or a request with an access token, of another login's account passes the gate; a request
  of a refused uid reaches the homeserver; any other account of the host reaches the homeserver without the gate, or
  the gate sends a token to a listener of another account.
- `doc/homeserver.md` describes the gate, and `doc/decisions.md` holds the decision "identity by the host's login,
  registration by the gate", with its accepted risks: whoever else may use a person's own machine can use their
  forward, and the gate takes the group at the time of each request.

## Open questions

- Whether a person's own client may sign in by their host login alone, with no password, if the homeserver can make
  a login token for the gate: one secret less, and the login on the host as the only proof.

## To be checked

- Where the homeserver listens so only its account reaches it: a Unix socket of Tuwunel's own in a directory of mode
  0700, or a network namespace with no port on the host; measured on Tuwunel.
- How long a sync held open passes through the gate, and how many open connections the gate's unit allows.
- The file naming each project's admins, its path and form, agreed with the host's agent.
- How the gate's system service reaches the homeserver's account's files (its listener and registration token).
