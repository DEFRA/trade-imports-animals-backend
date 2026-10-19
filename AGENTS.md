# trade-imports-animals-backend

Spring Boot service that stores animals import notifications and publishes their outbox events. See `README.md` for set-up and running, and `docs/design-notes.md` for why the code is shaped as it is. Rationale goes in `docs/design-notes.md`, not in code comments.

## Checks

- `mvn verify` runs unit tests, integration tests (needs Docker) and the coverage gate.
- Checkstyle runs in the `validate` phase with `config/checkstyle/code-quality.xml`. Run it alone with `mvn checkstyle:check@code-quality`.

## Code quality

- A comment describes only the lines below it, and only when a name or extraction cannot. Never describe callers, other modules, a library, the UI or a test. No counts ("the one", "the only").
- Javadoc states only the contract of a public API. None on private helpers.
- No ticket keys, backlog or ruling ids, release names or session words in code, comments or test names.
- No history in code: no before/after narration, no `REMOVED_*`, no "renamed from".
- A name predicts what the code returns and changes. No positional boolean flags, no metaphors. One concept keeps one name; a rename covers its whole vocabulary.
- Set-up used by two tests goes in a shared helper, and promoting a helper deletes the local copies.
- Every branch that changes an outcome logs once with the ids support searches by. Pass the throwable to `log.warn`/`log.error`, never only `getMessage()`. No request or response bodies at INFO.
- A test name states what its assertions prove, and the test fails without the change it covers.

Canonical text: `.claude/rules/code-quality.md` in the trade-imports-workspace repo.
