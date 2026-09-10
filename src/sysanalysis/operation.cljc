(ns sysanalysis.operation
  "The closed vocabulary of operations the ISCO-08 2511 community
  systems-analysis actor may propose.

  Runtime: portable `.cljc` (pure data + pure predicates, no host interop).

  Why this namespace exists. Before it, the operation vocabulary lived in two
  places that could not disagree loudly: the README's prose, and the
  Governor's private `(= :propose-design op)` test plus one named escalating
  op. That made the Governor a *denylist* — it bound one named op and admitted
  everything else. Measured on the pre-change tree, against a registered
  client whose single requirement `R1` was registered and not covered at all:

      {:op :drop-production-system :effect :propose :confidence 0.95}
      => {:ok? true :hard? false :escalate? false :violations []}

  Admitted, and admitted as a *clean* verdict: no escalation, no human, and an
  empty violation list to show a reviewer. `:op nil` was admitted the same
  way, and so was `:rebuild-everything`.

  An actor whose operation set is open cannot be governed, because the
  governor is answering a question about a vocabulary nobody declared. So the
  vocabulary is declared here, once, as an allowlist, and
  `sysanalysis.governor` refuses anything outside it.

  Two disjoint maps:

  * `supported` — what the actor may propose. `:escalates?` and `:design-op?`
    are properties of the operation, not of the governor's mood, so they live
    beside it.
  * `reserved` — operations naming authority this cognitive actor does not
    hold: switching off a production system, granting access, removing the
    audit trail, binding the organization contractually. These are *declared*
    rather than merely absent so the refusal can say why. An undeclared op is
    a vocabulary error; a reserved op is an authority boundary. Conflating
    them would let a future edit `supported`-list one of them by accident.

  `:design-op?` is the field that closes the gap this repo shipped with. The
  coverage-totality invariant and both basis checks were gated on
  `(= :propose-design op)`, so `:approve-cutover` — the operation whose entire
  purpose is switching production to the new system — was exempt from all
  three. Measured on the pre-change tree, against the same registered client
  with one registered requirement `R1` and one registered component `K1`:

      {:op :approve-cutover :coverage {}            ...} => {:escalate? true :violations []}
      {:op :approve-cutover :coverage {\"R1\" [\"K9\"]} ...} => {:escalate? true :violations []}
      {:op :approve-cutover :coverage {\"R404\" [\"K1\"]} ...} => {:escalate? true :violations []}

  where `K9` belongs to a different client and `R404` is not a registered
  requirement. The escalating op reached a human with an **empty violation
  list** on the one operation whose premise is that production is about to
  change — the requirement with no component behind it, the foreign
  component, and the invented requirement were all invisible at the point of
  sign-off. Binding is a property of the operation, so it is declared here and
  the governor reads it, rather than the governor naming one op and forgetting
  the more dangerous one.")

(def supported
  "Operations the actor may propose.

  `:escalates?` true means human sign-off is required regardless of advisor
  confidence. `:design-op?` true means the proposal binds to this client's
  REGISTERED requirement set, and therefore must satisfy coverage totality and
  both basis checks — the requirement basis and the component basis.

  Only the two operations the README has always claimed are listed. The
  vocabulary is deliberately not widened here: this namespace exists to close
  an opening, and adding ops would be the opposite of that."
  {:propose-design
   {:escalates?  false
    :design-op?  true
    :summary "propose a design: a coverage map from registered requirements to registered components"}

   :approve-cutover
   {:escalates?  true
    :design-op?  true
    :summary "approve switching production to the designed system (always human sign-off)"}})

(def reserved
  "Operations reserved to someone this actor is not. Naming one in a proposal
  is a permanent hard block, never an escalation: escalation would imply a
  human could approve the *actor* doing it, and neither the responsible analyst
  nor an operator can delegate a production shutdown, an access grant, the
  removal of an audit trail, or a contractual commitment to a remote cognitive
  actor.

  This is the machine-readable form of the scope sentence the README has
  carried since the repo was created — the advisor only proposes. Prose in a
  README does not refuse anything."
  {:drop-production-system
   {:reason "production を落とす決定は system owner のもので、実施は責任者が行う（設計提案の範囲外）"}

   :grant-system-access
   {:reason "権限付与は system owner の決定であって、要件被覆の結論ではない"}

   :disable-audit-ledger
   {:reason "監査台帳を外すことは、この actor 自身の governance が依拠している証拠を消すこと"}

   :sign-client-contract
   {:reason "組織を契約上拘束する行為は委任できない（提案と署名は別の権限）"}})

(defn supported? [op] (contains? supported op))
(defn reserved? [op] (contains? reserved op))

(defn declared?
  "True if `op` is named anywhere in this vocabulary. An op that is neither
  supported nor reserved is undeclared — the governor refuses it."
  [op]
  (or (supported? op) (reserved? op)))

(defn escalates?
  "True if the operation itself always requires human sign-off. Unsupported
  ops are never reached by this predicate (the governor hard-blocks first), so
  a false here is not an admission."
  [op]
  (boolean (get-in supported [op :escalates?])))

(defn design-op?
  "True if the operation binds to this client's registered requirement set and
  must therefore satisfy coverage totality and both basis checks. False for
  undeclared and reserved ops, which the governor hard-blocks before this is
  consulted."
  [op]
  (boolean (get-in supported [op :design-op?])))

(defn reserved-reason [op] (get-in reserved [op :reason]))
