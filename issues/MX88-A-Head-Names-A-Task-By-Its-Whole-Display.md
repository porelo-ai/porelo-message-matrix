# MX88 — A Head Names A Task By Its Whole Display

**Status:** Review.
**Size:** S - one method in `MeantFor`, which a person's line and the room tool use, its tests and the contract's head rule.

**What must be true.** A task is shown in the room as `Agent Pi` or, when its name differs from its agent, as
`Agent Pi (fix)`. A head `Agent Pi:` names only the member shown `Agent Pi`, never `Agent Pi (fix)`, and a head
`Agent Pi (fix):` names only the member shown `Agent Pi (fix)`, never `Agent Pi`.

## Plan

- **`MeantFor.head` reads each address in the head the way a task is shown**, `Agent <Word>` optionally followed by
  ` (<short>)` (letters, numbers, `_`, `.`, `-`), the longest such form at each place, as a whole phrase. A member is
  named when that whole form equals its display name, case-sensitive. A suffix that names no member names nobody; no
  fallback to the bare name. Today a member shown `Agent Pi (fix)` is never named, and `Agent Pi (fix):` names
  `Agent Pi`.
- **An agent's name may hold hyphens:** Porelo shows `claude-code` as `Agent Claude-Code`, so `<Word>` is parts of
  letters, numbers and `_` joined by single hyphens, read whole: `Agent Claude-Code:` never names `Agent Claude`.
- `mayNameAnAgent`, the broadcast and the 300-code-point head are unchanged; the room tool's reading (`MeantFor.line`,
  `post`) takes the same rule.
- **`doc/transport.md`** ("Whom a line addresses") and **`doc/decisions.md`** name both forms.
- **The test that goes red first:** `MeantForTest`: with members `Agent Pi` and `Agent Pi (fix)`, `Agent Pi (fix): hi`
  names only the second; today it names only the first. Beside it: `Agent Pi: hi` only the first, both named in one
  head, `Agent Pi (other): hi` nobody.

- **Kept as designed, after the code reading:** a parenthesis that is no short name (a space, nested parentheses,
  other punctuation) leaves the bare name, so `Agent Core (cc Agent Matrix):` still names both; the shorts Porelo
  gives are single words. A head naming no current member is for everybody in the room tool, as `Agent Unknown:`
  always was; a person's file then has an empty `to`.

## Acceptance

- The new test fails against the old code and passes against the new one.
- The unit suite passes.
