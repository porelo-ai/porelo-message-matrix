# MX22 — A Homeserver Elsewhere Gets Its Registration Token

**Status:** Implementing; found by porelo's [B102](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b102)
walk, which could not set up a project on a homeserver elsewhere; the hand-over [MX12](README.md#mx12) needs too.
**Size:** S - one module: a setting read and checked beside `ca_file`, the token read from its file where
`Lifecycle` needs it today, its tests, and the setting in `doc/index.md` and `doc/transport.md`.

**What must be true.** A project whose `homeserver` is not this account's own can be set up, enrolled and joined
through Porelo: the person names where the homeserver's registration token is, once, and the transport reads it
from there; the token is never on a command line, in a log, or in a file another account can read.

## Why

`setup`, `enroll` and `join` on a homeserver elsewhere need `PORELO_MATRIX_REGISTRATION_TOKEN` (`doc/transport.md`),
and nothing can give it to them. Porelo runs a transport with a chosen environment (`HelperEnvironment.chosen`:
`PATH`, `HOME`, the locale, `XDG_*`), so a shell's variable or the daemon's never reaches it, and adds only what a
verb printed and it kept under `transport/matrix/…` in the vault; `vault put` refuses those names as reserved. So
the start fails with "PORELO_MATRIX_REGISTRATION_TOKEN is not set" (78), as that walk saw.

## Proposed shape

**(b) A setting `registration_token_file`**, an absolute path to a file holding the token, like `ca_file`:
- `Settings` takes it in `KEYS`, refuses a relative path or one that is no path (78), and `settings` reports it as
  a draft; a file another account can read is refused, since the token lets anyone register on that homeserver;
- `Lifecycle.Place.registrationToken` and `setup` read the token from the file when the setting is there; the
  variable stays as before for whoever sets it;
- on the account's own homeserver the setting is refused, since its token is the transport's own file.

**(a), the alternative:** porelo lets a person store the one field the transport declares in `describe` as asked
of a person, e.g. `porelo vault put transport/matrix/account --field PORELO_MATRIX_REGISTRATION_TOKEN`, a narrow
exception to the reserved names. It keeps the token in the vault, but changes porelo and the contract both read.

## Acceptance

- A project on a homeserver elsewhere is set up, a task enrolled and a person joined through Porelo, with the token
  only in the named file; seen on a machine.
- **The walk porelo's clearing left open:** porelo's `vault clear` of that project deactivates its accounts on that homeserver -
  the person's password login, taken before, is refused after it - and leaves `@porelo` and every other project
  there alone; a new vault and a start in the project then succeed. Walked on a rented machine, as the account's
  own homeserver was.
- Each refusal in the plan seen red first; the whole workflow green on the VM.

## Plan, for (b)

1. `SettingsTest`, red first: `registration_token_file` relative, no path, readable by others, and set for the own
   homeserver are each refused; an absolute file of the account's own is taken.
2. `Settings`, `Lifecycle` as above; `LifecycleIT` sets a project up against the test homeserver as "elsewhere",
   with the token in a file and no variable, red against today's code.
3. `doc/index.md`'s settings table and `doc/transport.md`; the changelog line (`Added`).
4. The VM run; then porelo's walk of a homeserver elsewhere can run.

## To be checked

- Nothing in the interface: it has no editor for a transport's settings; `project.yml` is written by hand.
- Whether [MX12](README.md#mx12)'s machines take the same file, or a token per machine.
