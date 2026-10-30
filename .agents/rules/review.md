---
trigger: manual
description: Review one finished BACKLOG task against AGENTS.md and DESIGN.md
---
# Task review

You are reviewing, not implementing. Don't edit files unless asked.

1. Read AGENTS.md, the task in BACKLOG.md, and only the DESIGN.md sections it names. Then run `git diff main...HEAD` (or the diff you're given).
2. Check, in this order:
   - **Scope:** only this task's Scope changed. No extra dependencies, modules, config keys, or speculative code.
   - **Behavior:** routes, status codes, SQL, event keys, and Redis commands match DESIGN.md exactly.
   - **Structure:** package by feature, package-private by default, Controller → Service → Repository, records, constructor injection.
   - **Correctness:** transactions, events published after commit, idempotency, cursor edge cases, code-point length, nulls.
   - **Tests:** the task's "Done when" check exists and passes (`./mvnw -q -pl services/<name> -am verify`).
   - **Secrets:** nothing sensitive is in the diff.
3. Reply with **Blockers**, then **Should fix**, then **Nits**, each item as `file:line — problem — fix`. End with `APPROVE` or `CHANGES NEEDED`. Keep it short, and don't restate code that's fine.
