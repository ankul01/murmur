---
name: review-pr
description: Reviews a GitHub pull request in this repo against AGENTS.md, BACKLOG.md and DESIGN.md, and posts inline review comments on the PR. Use when the user types /review-pr 12 or asks to review a PR.
---
# Review a pull request

Input: a PR number. If it's missing, show `gh pr list --state open` and ask which one.

You are reviewing only. Don't edit files, check out branches, commit, or push.

1. **Fetch:** `gh pr view N --json number,title,headRefName,body,files` and `gh pr diff N`. Take the task id (`TNN`) from the title.
2. **Context:** read AGENTS.md, that task in BACKLOG.md, only the DESIGN.md sections it names, and the checklist in `.agents/rules/review.md`. Open whole files only when the diff isn't enough.
3. **Findings:** at most 15, with no praise. Each one has:
   - the path
   - the line number in the PR's new version of the file
   - a severity: **Blocker**, **Should fix**, or **Nit**
   - the problem
   - the fix
4. **Post one review:**
   - Write `/tmp/review-N.json`:
     `{"event":"COMMENT","body":"<summary>\n\nBlockers: X · Should fix: Y · Nits: Z\nVerdict: APPROVE|CHANGES NEEDED","comments":[{"path":"…","line":42,"side":"RIGHT","body":"**Blocker** — <problem>. Fix: <fix>"}]}`
   - Run `gh api repos/{owner}/{repo}/pulls/N/reviews --method POST --input /tmp/review-N.json`.
   - Always use `"event":"COMMENT"`. The same GitHub account opened the PR, and GitHub doesn't allow approving or requesting changes on your own PR.
   - Findings on lines outside the diff go in `body`, because the API rejects them as inline comments.
   - The verdict is `CHANGES NEEDED` if there is any Blocker or Should fix item.
5. **Reply:** the verdict, the counts, and the PR URL.
