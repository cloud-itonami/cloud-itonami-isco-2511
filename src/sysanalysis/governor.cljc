(ns sysanalysis.governor
  "SystemsAnalysisGovernor — the independent safety/traceability layer for the
  ISCO-08 2511 community systems-analysis actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Systems-analysis twist: a
  design's requirement coverage is the TOTALITY of a relation — every
  registered requirement maps to at least one registered component, or the
  design is incomplete. Coverage is checked, not claimed.

  The invariant that binds the whole thing: **a check is never skipped
  silently.** Where a comparison cannot be made, `sysanalysis.facts` has
  already recorded why as a violation of its own, so an unusable value produces
  a refusal rather than an admission.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. vocabulary        — the op must be declared in
                           `sysanalysis.operation/supported`. A reserved op
                           (production shutdown, access grant, removal of the
                           audit trail, contractual commitment) is reported
                           separately from an undeclared one.
    2. client provenance — the store's record must identify the client the
                           request names.
    3. no-actuation      — proposal :effect must be :propose.
    4. coverage envelope — a design-binding operation must declare a readable
                           coverage map whose keys are citable requirement ids
                           and whose values are readable component id lists.
    5. requirement set   — the client must have at least one registered
                           requirement, and every registered requirement must
                           itself be citable.
    6. requirement basis — coverage keys must all be REGISTERED requirements of
                           this client (no invented requirements).
    7. component basis   — every cited component must be REGISTERED and belong
                           to this client.
    8. coverage totality — every registered requirement must be covered by a
                           non-empty component set (an uncovered requirement is
                           arithmetic absence, not an open question).
    9. usable confidence — a present :confidence must be a number in [0,1].
  ESCALATION invariants (:escalate? true, human sign-off):
   10. the operation declares `:escalates?` — currently :approve-cutover
                           (switching production to the designed system).
   11. low confidence (< `confidence-floor`).

  Invariants 4 through 8 now apply to EVERY design-binding operation, not only
  to `:propose-design`. That is the substantive change: an `:approve-cutover`
  used to reach a human with an empty violation list while carrying an
  uncovered requirement, a foreign component, or an invented requirement. The
  measurements are in the docstrings of `sysanalysis.operation` and
  `sysanalysis.facts`."
  (:require [clojure.set :as set]
            [sysanalysis.facts :as facts]
            [sysanalysis.operation :as op]
            [sysanalysis.store :as store]))

(def confidence-floor 0.6)

(defn- coverage-relation-violations
  "The coverage checks proper — the basis on both sides and the totality
  between them — for operations that bind to the registered requirement set.

  Each check is guarded by the values being usable, but the guard is not a
  silent skip: `facts/coverage-violations` and
  `facts/requirement-record-violations` have already reported an unusable value
  as a violation, so a guarded-out check never turns into an admission. That
  pairing is the whole reason those functions exist.

  Unreadable coverage is normalized to `{}` here rather than skipped, so the
  totality difference still reports every registered requirement as uncovered.
  Both directions are refusals; neither is an admission."
  [proposal reqs]
  (let [{:keys [op coverage]} proposal]
    (if-not (op/design-op? op)
      []
      (let [cov       (if (map? coverage) coverage {})
            ;; `keep`, not `map`: a requirement registered without an id is
            ;; reported by facts/requirement-record-violations, and must not
            ;; enter the id set — otherwise a `{nil [...]}` coverage entry
            ;; satisfies totality against a record that names nothing.
            req-ids   (into #{} (keep :req-id) reqs)
            covered   (into #{} (comp (filter (fn [[_ comps]]
                                                (and (coll? comps) (seq comps))))
                                      (map key))
                            cov)
            invented  (set/difference (into #{} (keys cov)) req-ids)
            uncovered (set/difference req-ids covered)]
        (cond-> []
          (seq invented)
          (conj {:rule :unknown-requirement
                 :detail (str "未登録要件を被覆表に記載 " (vec invented)
                              "（要件の捏造禁止）")})

          (seq uncovered)
          (conj {:rule :uncovered-requirement
                 :detail (str "未被覆の登録要件 " (vec uncovered)
                              "（被覆は関係の全域性であって主張ではない）")}))))))

(defn- component-violations
  "The component basis: every cited component is registered and belongs to this
  client. Only component references that are readable as ids are looked up —
  an unreadable value has already been reported by
  `facts/coverage-violations`, and looking it up is what made the pre-change
  tree report each CHARACTER of a string id as a missing component."
  [request proposal store]
  (if-not (op/design-op? (:op proposal))
    []
    (let [cov (when (map? (:coverage proposal)) (:coverage proposal))]
      (into []
            (keep (fn [comp-id]
                    (let [c (store/component store comp-id)]
                      (cond
                        (nil? c)
                        {:rule :unknown-component
                         :detail (str "未登録 component: " comp-id)}
                        (not= (:client-id c) (:client-id request))
                        {:rule :comp-wrong-client
                         :detail (str "component が別 client のもの: " comp-id)}))))
            (into #{}
                  (comp (map val)
                        (filter facts/component-refs?)
                        cat)
                  cov)))))

(defn- hard-violations [request proposal client-record reqs store]
  (vec (concat (facts/vocabulary-violations proposal)
               (facts/provenance-violations request client-record)
               (when (not= :propose (:effect proposal))
                 [{:rule :no-actuation
                   :detail "effect は :propose のみ許可（直接書込禁止）"}])
               (facts/coverage-violations proposal)
               (facts/requirement-set-violations (:op proposal) reqs)
               (facts/requirement-record-violations (:op proposal) reqs)
               (facts/confidence-violations proposal)
               (coverage-relation-violations proposal reqs)
               (component-violations request proposal store))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `sysanalysis.store/Store`. Pure — never mutates the store.

  Returns `{:ok? :violations :confidence :hard? :escalate?}`. `:hard?` is
  checked before `:escalate?` by every caller (see `sysanalysis.phase`): a
  proposal that is both hard-blocked and escalating must hold, because
  escalating it would ask a human to approve something no human may approve."
  [request _context proposal store]
  (let [client-record (store/client store (:client-id request))
        reqs (store/requirements-of store (:client-id request))
        hard (hard-violations request proposal client-record reqs store)
        hard? (boolean (seq hard))
        raw-conf (:confidence proposal)
        conf (if (facts/usable-confidence? raw-conf) raw-conf 0.0)
        low? (< conf confidence-floor)
        risky-op? (op/escalates? (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
