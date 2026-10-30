---
name: ship
description: Commits the current BACKLOG task's changes on its task branch, pushes, and opens a pull request to main. Use when the user types /ship T02, or says "commit this" or "raise a PR" for a task.
---
# Ship a task

Input: a task id such as `T02`. If it's missing, ask for it.

1. **Preflight:** `gh auth status` must succeed. If `gh` is missing, stop and tell the user to run `brew install gh && gh auth login`. Don't install it yourself.
2. **Branch:** never commit to `main`.
   - On `main`: create `t02-<slug>`, where the slug is 2–4 lowercase words from the task title, joined with hyphens.
   - Already on a `t02-*` branch: stay on it.
3. **Check:** for each module you touched, run `./mvnw -q -pl <module> -am verify` (or `./mvnw -q verify` if you're unsure), plus the task's "Done when" commands. Stop if anything fails.
4. **Stage safely:**
   - Run `git add -A`.
   - Run `git diff --cached --name-only`. If it lists `.env*` (other than `.env.example`), `*.pem`, `*.key`, or `target/`, unstage them and stop.
   - If `gitleaks` is installed, run `gitleaks protect --staged`.
5. **Commit:** `git commit -m "feat(T02): <task title>" -m "<Done-when commands and results>"`. Use `fix(T02): …` for changes after a review.
6. **Push:** `git push -u origin HEAD`.
7. **PR:**
   - If `gh pr view --json number` finds an open PR for this branch, the push has already updated it.
   - Otherwise, run `gh pr create --base main --title "T02 · <task title>" --body "<Scope done / Done-when output / Out of scope>"`.
8. **Reply:** only the PR number and URL. Never merge.
