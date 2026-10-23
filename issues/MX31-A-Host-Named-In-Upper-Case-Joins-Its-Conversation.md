# MX31 — A Host Named In Upper Case Joins Its Conversation

**Status:** Review.
**Size:** XS - one option of the lifecycle verbs, its integration test and a line of the contract.

**What must be true.** A host whose name carries upper-case letters takes part in its project's conversation like
any other: the transport takes `--machine` without regard to case, so a task start on it is never refused for its
name.

## Plan

- Porelo hands the transport the host's name as `--machine`, and a host name is case-insensitive. `Lifecycle.options`
  holds `--machine` to the same lower-case rule as `--project` and `--task` and exits 64 for `Host-A`, so such a
  host's first task start ends 69 with "the matrix transport could not setup".
- `--machine` is folded to lower case (`Locale.ROOT`) before it is checked; `--project`, `--task` and `--person`
  stay exact, since they are Porelo's own names. Every other character a host name may hold already passes, and
  the name `setup` keeps as `PORELO_MATRIX_MACHINE` was lower case already, so `centralMachine` compares like with
  like.
- **Red first,** in `CentralIT`: `setup --machine Host-A` on a central homeserver registers `@porelo.host-a` and
  keeps `PORELO_MATRIX_MACHINE` as `host-a`, and a later `enroll` with `--machine HOST-A` is not refused as another
  machine. Today `setup` exits 64.
- `doc/transport.md` says `--machine` is taken without regard to case; the changelog gains its line under *Fixed*.

## Acceptance

- `CentralIT`'s new test and `DescribeTest`'s `Node-1` are seen red against today's code (64) and green with the
  fold.
- A `--machine` with a character no host name holds (a space, a leading dash, `:` or `@`) is still refused with 64,
  as `DescribeTest` holds.
