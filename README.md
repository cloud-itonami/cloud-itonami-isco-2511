# cloud-itonami-isco-2511

Open Business Blueprint for **ISCO-08 2511**: Systems Analysts — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — SystemsAnalysisAdvisor ⊣
SystemsAnalysisGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
70 tests / 269 assertions green, plus a 24-scenario governed-scenario
harness that demonstrates 23 refusals.

The systems-analysis HARD invariant — requirement coverage is the
TOTALITY of a relation, checked deterministically:

1. **Coverage totality** — every registered requirement must map to a
   non-empty set of registered components. An uncovered requirement is
   arithmetic absence, not an open question.
2. **Basis integrity** — coverage may only cite registered
   requirements and this client's registered components (no invented
   requirements, no foreign components).

Also HARD: undeclared or reserved operation, unregistered or
unidentified organization, non-`:propose` effect, unreadable coverage,
an empty or uncitable registered requirement set, and an unusable
`:confidence`. Escalations (always human sign-off):
`:approve-cutover` (production switch), low confidence (< 0.6).

## Namespaces

| namespace | what it owns |
|---|---|
| `sysanalysis.operation` | the **closed vocabulary** — `supported` ops and `reserved` authority boundaries, with `:escalates?` and `:design-op?` declared beside each |
| `sysanalysis.facts` | well-formedness of the governed values: client record, registered requirements, coverage envelope, confidence |
| `sysanalysis.governor` | the invariants proper — vocabulary, provenance, no-actuation, both bases, totality, confidence |
| `sysanalysis.advisor` | proposes only; swappable mock / LLM |
| `sysanalysis.phase` | `verdict` → one of `:hold` / `:request-approval` / `:commit`, and what each phase may do |
| `sysanalysis.ledger` | hash-chained append-only audit trail + approval provenance |
| `sysanalysis.store` | SSoT protocol + in-memory implementation |
| `sysanalysis.actor` | the wired StateGraph |
| `sysanalysis.sim` | deterministic governed-scenario harness (below) |

## Running it

```bash
clojure -M:test   # unit tests
clojure -M:sim    # governed-scenario harness over the real StateGraph
clojure -M:lint   # clj-kondo, errors fail
```

### The scenario harness refuses to report a pass on a green nothing

`clojure -M:sim` runs a table of requests through the **real** graph and
counts refusals. It exits non-zero when the refusal count is zero:

```
  scenarios=1 refusals=0 mismatches=0 unreasoned=0 wrote-anyway=0 ledger-breaks=0
  REFUSING TO REPORT A PASS: the scenario table demonstrated no refusal.
```

A governed actor's claim is not that it acts — it is that there exist
actions it refuses. A harness running only clean scenarios would print
green while demonstrating nothing.

Every refusal scenario also declares `:because`, the violation rule that
must appear in the verdict, and every admissible one declares `:clean?`,
which asserts the violation list is empty. Asserting only the phase lets
a run that failed for an unrelated reason count as a demonstration —
measured: of seven mutations that each restore one of the defects below,
three left the phase column entirely green.

## What was open before `operation` / `facts` / `phase` / `ledger` existed

Every line below was measured on the tree this repo shipped with, against
a registered client with registered requirements and components. The
per-namespace docstrings carry the full transcripts.

| input | pre-change verdict |
|---|---|
| `{:op :drop-production-system :confidence 0.95}` | `{:ok? true :violations []}` |
| `{:op :rebuild-everything ...}` / `{:op nil ...}` | `{:ok? true :violations []}` |
| `:approve-cutover` with a wholly uncovered requirement | `{:escalate? true :violations []}` |
| `:approve-cutover` citing another client's component | `{:escalate? true :violations []}` |
| `:approve-cutover` citing an unregistered requirement | `{:escalate? true :violations []}` |
| client registered as `{}`, request with no `:client-id` | `{:ok? true :violations []}` |
| design for a client with zero registered requirements | `{:ok? true :violations []}` |
| requirement with no `:req-id`, coverage `{nil ["K1"]}` | `{:ok? true :violations []}` |
| component with no `:comp-id`, coverage `{"R1" [nil]}` | `{:ok? true :violations []}` |
| `:confidence 99.0` | `{:ok? true}` — not escalated |
| `:confidence "high"` | **threw on `:clj`, admitted on `:cljs`** |
| `:coverage ["R1"]` | threw (`ISeq from Character`) |
| `:coverage {"R1" "K1"}` | refused, reporting `"K"` and `"1"` as missing components |

The shape they share: the governor bound one op name (`:propose-design`)
and admitted everything outside it, so the operation whose entire purpose
is switching production — `:approve-cutover` — was exempt from the
invariant the repo is named for. Two tests in this repo passed because
they encoded that exemption; both now supply the coverage a cutover must
show, and the exemption itself is covered by new tests.

`:confidence "high"` is the one worth reading twice: the same tree threw
on Clojure and returned `{:ok? true :hard? false :escalate? false}` under
ClojureScript, because `(< "high" 0.6)` compiles to the JavaScript
`"high" < 0.6`, which is `false`.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
