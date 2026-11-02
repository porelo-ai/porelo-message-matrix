# MX68 — The Gate's Refusals Are Held By Tests

**Status:** Implementing.
**Size:** M - about twenty cases in one test class against its fake backend, no production code unless a test finds a defect.

**What must be true.** Whoever removes one of the gate's refusals of a login, a registration or a reserved alias sees a unit test fail. Today mutation testing can remove these and every unit test passes.

## Plan

`GateTest`, with its existing fake backend. What each case holds, none of it held today:

- `aliasRefusal`: a `PUT` of `directory/room/#porelo-<project>:<server>` by someone who is no project admin is 403 and nothing is forwarded, as is a `PUT` not starting with `#`, an invalid project name and an unreadable admin list. Only a `GET` of the same path passes the check by.
- `login`: an `identifier` naming the caller beside a `user` naming another is 403; an `identifier` of a type other than `m.id.user` is 403; a body that is no JSON is 400; none reaches the homeserver.
- `login` by token: the homeserver refusing the administrator's login is answered as it came, with no session.
- `register`: a full name on another server is `M_INVALID_USERNAME`; an empty or non-text password and an `auth` other than `m.login.dummy` are `M_BAD_JSON`; a taken name is `M_USER_IN_USE`; a failed creation is answered and no login follows; a success is a session the gate built, not the homeserver's answer.
- `sso`: a taken account goes on to a login token without being created; any other availability answer stops it; a failed creation stops it.

Red first: each test is seen to fail against its mutant, by PIT or by removing the check by hand. A test that does not fail is not done.

## Acceptance

- Measured: PIT on `Gate` kills every mutant of the methods above, or the issue names the mutant and why no input tells it apart.
- Seen to fail: each new case, against the removed check.
- A test that finds a real defect stops here: the fix follows that failing test, and the defect is said in the channel.
