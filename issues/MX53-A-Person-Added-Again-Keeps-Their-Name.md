# MX53 — A Person Added Again Keeps Their Name

**Status:** Idea.

**What must be true.** A person removed from a prepared host and added again later under the same user name gets
their Matrix accounts on the host's shared homeserver back, under the same names, and nobody else can take them over
in between.

## The shape

The contract, in words:

- **Locking instead of deactivating.** `machine-token expel M` no longer deactivates `@….<M>`. It locks each account
  instead: it sets a password of its own, logs in with it and ends every session, takes the account out of every room,
  and last sets a password nobody keeps. The name stays the account's, and nobody can log in to it.
- **`machine-token issue M` for an expelled machine is no longer refused.** Besides the token it answers one one-time
  password per locked account, among its secrets only:
  `{"machine":"M","token":"…","takeback":{"@porelo.M:…":"…","@task.M:…":"…"}}`. A one-time password holds until it
  is used once or a later `expel M` replaces it. Nothing of it appears on stderr, in a file name or a log line.
- **The person's Porelo takes each account back.** It hands the one-time passwords to the transport's `setup` on its
  input, as it hands every secret. `setup` logs in once with each, ends every other session, and sets the password
  derived from the machine's own secret, so the host admin's helper never learns it. An account without a one-time
  password is refused by name, never registered under another name.
- **Who changes what:** this transport (`expel`, `issue`, `setup`), Porelo (handing the one-time passwords from the
  helper's answer to `setup`), and the host's tooling (passing `issue`'s answer on to the person's Porelo).

## Acceptance

- A person expelled and then issued a token again logs in to `@porelo.<M>` and `@task.<M>` with the passwords their
  own Porelo derives, and the one-time passwords log in no more.
- While the person is expelled, no password, token or registration logs in to or takes over any `@….<M>`, measured.
