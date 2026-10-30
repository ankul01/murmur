# murmur (learning project)

A hands-on Murmur for learning timeline fan-out (write, read, and hybrid) and AWS on a $0 Free plan.

- Design: [DESIGN.md](DESIGN.md)
- Tasks: [BACKLOG.md](BACKLOG.md), starting at T00
- Rules for code and agents: [AGENTS.md](AGENTS.md)

## Agent skills (optional, install in T00)

AGENTS.md always wins over any skill. Keep installed skills to 1–3, because every skill an agent loads costs tokens.

| Skill | Why | Install |
| --- | --- | --- |
| forrestchang/andrej-karpathy-skills | About 65 lines: think first, simplest code, surgical changes, verify. It cuts over-building and wasted tokens. | `npx skills add forrestchang/andrej-karpathy-skills` |
| decebals/claude-code-java: `java-code-review` only | A Java review checklist to run after each task | `npx skills@latest add decebals/claude-code-java --skill java-code-review` |
| piomin/claude-ai-spring-boot: `conventions` (reference) | Spring Boot 4 / Java 21 rules that match ours: no Lombok, constructor injection, RestClient. Read it, but don't install its JPA or Kubernetes skills. | Read it on GitHub, and copy rules into AGENTS.md if useful |

Don't install `spring-boot-patterns`-style skills that teach layered packages or JPA. They conflict with AGENTS.md.

## Tools

- **Cursor** (coding) and **Antigravity** (review) both read `AGENTS.md` automatically. `CLAUDE.md` only matters if you also use Claude Code.
- **Review in Antigravity:** mention `@review` in the agent box, which uses `.agents/rules/review.md`.
- Run one task per branch. If two Cursor windows work at the same time, give each its own `git worktree`.
