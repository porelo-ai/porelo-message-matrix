# The Matrix transport

The Matrix transport for Porelo: the tasks of a project talk in one Matrix room, and a person can read and
write in it with any Matrix client. Porelo runs the transport itself; you install it and name it in a
project.

This chapter holds:

- this page: installing the transport, using it in a project, and joining the conversation as a person;
- [The local homeserver](homeserver.md): where it keeps its data, its settings, and how to reset it;
- [The transport](transport.md): the program's verbs and exit codes, as Porelo runs it;
- [Decisions](decisions.md): why it is built this way, and the risks accepted.

## Install

From the same package repository as Porelo, the transport and - for a homeserver on this machine - the
local homeserver:

    sudo apt-get install porelo-message-transport-matrix porelo-matrix-homeserver

Porelo's "new machine" setup offers both. The homeserver is never pulled in by the transport, so a machine
that uses a central homeserver does not get one.

## Use it in a project

In the project's `project.yml`:

```yaml
mail:
  transports:
    matrix: {}
```

That is all for the local homeserver: the project's first task start brings it up, makes the project's
room and an account for each task. Each task is then a peer the others can write to.

For a homeserver elsewhere, name it, and say how its certificate is trusted if it is not from a public
authority:

| Setting | What |
|---|---|
| `homeserver` | The homeserver's URL, e.g. `https://matrix.example.org`; plain `http` only on this machine's loopback. Absent: this machine's own |
| `ca_file` | An absolute path to a PEM file: an intranet's CA, or a self-signed server's certificate |
| `tls_verify` | `off` checks no certificate, and warns on stderr at every start. For development only, never for use |
| `registration_token_file` | For a homeserver elsewhere: an absolute path to a file holding its registration token, readable by this account alone (`chmod 600`) |

A setting the transport does not know is refused, and so is a relative `ca_file`; the interface's
editor says so before anything is committed.

## Read along and write as a person

    porelo talk join <project> <your name>

prints a login, once: the homeserver, your user, the room and a password. On the local homeserver,
forward its port (8008 unless taken) to reach it from your computer, and sign in with any Matrix client.
`--reset` gives you a new password.

What you write in the room reaches every task of the project. Mention a task (pick it from your client's
list) or reply to its message to say it is meant for that one. To write to one task alone, open a direct
chat with its account from the room's member list, with encryption switched off (nheko and Element turn
it on by default, and a task cannot read an encrypted chat: it declines one and says why). The task's
answer to you appears there.
