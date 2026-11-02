# MX67 — The Registration Token Comes Only With The Secrets

**Status:** Review.
**Size:** S - one setting less in `Settings`, `SettingsCheck` and `Lifecycle.elsewhereToken`, its tests, the contract's text.

**What must be true.** A homeserver elsewhere's registration token is kept like a person's other tokens, in their vault,
and Porelo lends it to the transport among the secrets on its input. No file on a machine holds it, and no project's
settings name one.

## Why

The setting `registration_token_file` exists because Porelo once handed a transport no token of the person's own. Porelo
now hands secrets on the transport's input, which already reads `PORELO_MATRIX_REGISTRATION_TOKEN` there. A file beside
it is a second form of the same thing, and one more place a token lies in plaintext on a host. This contract changes
together with Porelo's lending of the token; until Porelo lends it, the file is the only way the token arrives.

## Plan

- `Settings`, `SettingsCheck`: `registration_token_file` is refused (78) like any other unknown setting.
- `Lifecycle.elsewhereToken`: only `Config.REGISTRATION_TOKEN`, and the refusal names the vault as its source.
- `RegistrationTokenFile.read` goes; `firstToken` stays, for the local homeserver's own file.
- `Describe`: `credentials` gains `{"name":"PORELO_MATRIX_REGISTRATION_TOKEN","as":"env","lent":["setup","enroll"]}`, so
  Porelo lends the token on those verbs, the only ones that register on a homeserver elsewhere, and names no transport.
- `doc/transport.md`, `doc/homeserver.md`, the change log.
- Red first: `LifecycleIT`: settings naming `registration_token_file` are refused with 78 and nothing is registered. Today
  they pass.

## Acceptance

- Measured against a real homeserver with registration tokens: a first `setup` and a first `enroll` with the token on
  their input register the account; with none each ends 78, naming the token, and registers nothing.
- Seen to fail: a token in the environment of a Porelo that hands secrets on the input is used; the token appears in an
  answer, a log or an error.
