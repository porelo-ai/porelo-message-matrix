# MX82 — A Local Agent's Line Reaches A Moved Task

**Status:** Analyzed.
**Size:** S - `poll` already hands over any member's line that carries both fields; an integration test holds that
for a local agent's account, the documents state the form, and the room client beside it signs.

**What must be true.** While agents move into tasks, a line a local agent on the development machine posts in the
project's room reaches a task on a host as that agent's message, signed by the agent's own key and believed through a
certificate a person signed for it. A line without such a signature still never reaches a task as anybody's words.

## Why

[MX76](README.md#mx76) built the tunnel, the `unix:` homeserver and the script that lays certificates on a host. But the
room client the local agents use still posts plain `m.text`. A moved task takes plain text only from a person's account
(`poll --persons`), and refuses every `@porelo-…` account as a person. No local agent has a key or a certificate
either. So a moved task hears only the person, and the coordinating agent cannot reach it.

## The shape

- **The line's form is the transport's own:** an `m.text` event whose `body` is readable as today, beside
  `org.fuin.porelo.message` (the message, a document Porelo's intake reads) and `org.fuin.porelo.signature` (an SSH
  signature over that message's exact bytes in the namespace `porelo-message`, the armored signature as base64). `poll` hands it over as a message
  file with its `.sig` beside it, as for a task's own line.
- **The message is an agent's:** role `ROLE_AGENT`, `metadata.from` the agent's plain name (the `<name>` of its
  principal, never a Matrix id, which the filter refuses as a malformed peer name).
- **Each local agent has an Ed25519 key of its own**, made once, used only to sign its lines. A person signs a user
  certificate on its public key with one principal, `task:<project>:<name>`, valid for the time agents move. The
  signer must be one of the project's certifiers. `build/lay-certificates.sh` lays the certificates on the host. It
  signs nothing, and the signature stays the person's deliberate step.
- **Local agents' names are no task's names** (`porelo-agent-<role>`), so none counts against a real task's
  `per_day`, and they stay external to the filter.
- **The transport decides nothing:** it carries the two fields and never checks them. Porelo's `MessageDelivery`
  holds a line whose signature or certificate does not hold, and the filter judges the rest.
- **What it is not:** no second way for a person's words, and no trust in room membership.

## Plan

- **Red first:** `CentralIT#aLocalAgentsSignedLineReachesAMovedTaskAsAMessage`. Against Tuwunel, an account
  `@porelo-agent-test` posts an `m.text` with `org.fuin.porelo.message` and `org.fuin.porelo.signature`. The task's
  `poll` must hand over exactly those bytes and the `.sig` beside them. A plain-text line from the same account must
  still be handed over as nothing. Seen to fail when `poll` skips the line by its sender, or when it hands over the
  `body` instead of the message.
- **Read today in `Poll.deliver`:** a line is a Porelo message by `org.fuin.porelo.signature` beside a textual
  `org.fuin.porelo.message`, whoever sent it. The signature field is the base64 of the armored SSH signature, written
  as `<name>.json.sig`, and the sender goes into `<name>.json.sender`. So no code changes; the test holds it, and the
  shape of the message is not parsed.
- **`doc/transport.md`:** the line's form, as a contract a client outside Porelo can post in. **`doc/homeserver.md`**
  under "A host reaching the room on the development machine": the key per agent, the signing command with the
  card key in an agent (`ssh-keygen -s <certifier's public key> -U -I <name> -n task:<project>:<name> -V <window>
  <agent's public key>`), and laying the certificates with `build/lay-certificates.sh`.
- **The room client beside it** (kept outside any repository): it makes the agent's key on first use, and posts each
  line in the form above, signing with `ssh-keygen -Y sign -n porelo-message`. It keeps the readable `body`, so
  people and agents read the room as today. Its own acceptance run covers it.

## Acceptance

- The integration test above is green and was seen red twice, on each of the faults named.
- On a local VM, a task whose project names the development machine's homeserver gets a signed line of a local agent
  as a message from `porelo-agent-<role>`, after `build/lay-certificates.sh` laid that agent's certificate. Without
  the certificate it is held, and a plain line from the same agent never arrives.
- A person's line still reaches the task as a person's words.

## Open questions

- **Where the signing client is built:** (a) in the local room client beside this repository, with this repository
  holding the form by its test and documents (recommended: no change of how agents hold their room credentials); or
  (b) as `room post --sign` of this transport, tested here, which also moves the agents' room credentials into the
  vault (size M).
- **The agents' private signing keys at rest:** an unencrypted key file readable only by its owner, like the
  agents' room credentials today, is an accepted risk until an encrypted store with a supported read path exists;
  or each key is held in an ssh agent, unlocked once per session.

## To be checked

- That the person's card key is among project porelo's certifiers, read with `porelo org signers`.
- That the room accepts an event of 10240 bytes with both fields, the transport's `max_bytes`.
