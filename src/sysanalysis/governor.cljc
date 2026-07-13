(ns sysanalysis.governor
  "SystemsAnalysisGovernor — the independent safety/traceability layer
  for the ISCO-08 2511 community systems-analysis actor (itonami actor
  pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Systems-analysis
  twist: a design's requirement coverage is the TOTALITY of a relation
  — every registered requirement maps to at least one registered
  component, or the design is incomplete. Coverage is checked, not
  claimed.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. requirement basis — coverage keys must all be REGISTERED
                           requirements of this client (no invented
                           requirements).
    4. component basis   — every cited component must be REGISTERED
                           and belong to this client.
    5. coverage totality — every registered requirement must be
                           covered by a non-empty component set
                           (an uncovered requirement is arithmetic
                           absence, not an open question).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-cutover (production switch).
    7. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [sysanalysis.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record reqs]
  (let [{:keys [op coverage]} proposal
        design? (= :propose-design op)
        req-ids (into #{} (map :req-id) reqs)
        covered (into #{} (comp (filter (fn [[_ comps]] (seq comps)))
                                (map key))
                      coverage)
        invented (set/difference (set (keys coverage)) req-ids)
        uncovered (set/difference req-ids covered)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and design? (seq invented))
      (conj {:rule :unknown-requirement
             :detail (str "未登録要件を被覆表に記載 " (vec invented)
                          "（要件の捏造禁止）")})

      (and design? (seq uncovered))
      (conj {:rule :uncovered-requirement
             :detail (str "未被覆の登録要件 " (vec uncovered)
                          "（被覆は関係の全域性であって主張ではない）")}))))

(defn- component-violations [request proposal store]
  (when (= :propose-design (:op proposal))
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
          (into #{} (mapcat val) (:coverage proposal)))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `sysanalysis.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        reqs (store/requirements-of store (:client-id request))
        hard (into (hard-violations {:request request :proposal proposal}
                                    client-record reqs)
                   (component-violations request proposal store))
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-cutover (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
