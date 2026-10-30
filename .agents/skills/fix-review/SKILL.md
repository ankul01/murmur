---
name: fix-review
description: Pulls review comments from a GitHub pull request, implements the fixes on its branch, pushes, and replies to each comment. Use when the user types /fix-review 12 or asks to address review comments on a PR.
---
# Fix review comments

Input: a PR number. If it's missing, ask for it.

1. **Checkout:** `gh pr checkout N && git pull`.
2. **Fetch the comments:**
   - The latest review summary: `gh pr view N --json reviews --jq '.reviews[-1].body'`
   - The inline comments: `gh api repos/{owner}/{repo}/pulls/N/comments --jq '.[] | {id, path, line, body, in_reply_to_id}'`
   - Keep only top-level comments (no `in_reply_to_id`) that don't already have a "Fixed in" or "Won't fix" reply.
3. **Plan:** list them by severity. Then:
   - Implement every **Blocker** and **Should fix**.
   - Implement a **Nit** if it's under 5 lines; otherwise list it.
   - If a comment conflicts with AGENTS.md or DESIGN.md, don't change the code; reply with the reason instead.
   - Stay inside the task's Scope, and add no new dependencies.
4. **Check:** run `./mvnw -q -pl <module> -am verify` and the task's "Done when" commands. Stop if anything fails.
5. **Commit and push:** `git add -A`, run the same secret check as in /ship, then `git commit -m "fix(TNN): address review on #N"` and `git push`.
6. **Reply to each comment:** `gh api repos/{owner}/{repo}/pulls/N/comments/<id>/replies -f body="Fixed in <short sha>: <one line>"` (or `"Won't fix: <reason>"`).
7. **Reply in chat:** what changed, what was skipped, and "Ready for /review-pr N".

**Loop guard:**
- If this is the 3rd review round on this PR, stop and ask the human.
- When a review says `Verdict: APPROVE`, tell the user to merge it themselves: `gh pr merge N --squash --delete-branch`.
