# cloud-itonami-isco-2511

Open Business Blueprint for **ISCO-08 2511**: Systems Analysts — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — SystemsAnalysisAdvisor ⊣
SystemsAnalysisGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
14 tests / 29 assertions green.

The systems-analysis HARD invariant — requirement coverage is the
TOTALITY of a relation, checked deterministically:

1. **Coverage totality** — every registered requirement must map to a
   non-empty set of registered components. An uncovered requirement is
   arithmetic absence, not an open question.
2. **Basis integrity** — coverage may only cite registered
   requirements and this client's registered components (no invented
   requirements, no foreign components).

Also HARD: unregistered organization, non-`:propose` effect.
Escalations (always human sign-off): `:approve-cutover` (production
switch), low confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
