(ns sysanalysis.sim
  "Deterministic governed-scenario harness for the ISCO-08 2511 community
  systems-analysis actor: run a table of requests through the real StateGraph
  and report which ones the governor refused.

  Runtime: `run` and `report` are portable `.cljc`. `-main` is `:clj`-only,
  because process exit codes are a host concern; the `:cljs` branch throws
  rather than pretending to exit.

  Why this namespace exists, and why it fails loudly. A governed actor's claim
  is not that it acts — it is that there exist actions it refuses. A harness
  that ran only clean scenarios would print green while demonstrating nothing,
  which is the shape this workspace has repeatedly caught: a check that could
  not fail returning the same value as a check that passed.

  So `run` counts refusals, and `-main` exits non-zero when the count is zero.
  A scenario table that has stopped exercising the governor is a defect in the
  table, and it is reported as one rather than as a pass.

  The four questions this harness answers that a unit test does not:
    * does the *wired graph* refuse, or only the pure `check` function
    * does it refuse for the reason it names, or for some other reason that
      happens to produce the same phase
    * does an escalated request actually interrupt rather than write
    * does the ledger it leaves behind verify, and does it record who approved
      each write

  The second one is why every refusal scenario carries `:because`, a violation
  rule that must appear in the verdict, and every admissible one carries
  `:clean?`, which asserts the violation list is empty. Measured while building
  this table: of seven mutations that each restore one of the defects this
  repo shipped with, three left the phase column completely green, because a
  scenario asserting only the phase counts a run that failed for an unrelated
  reason as a demonstration. Adding `:because` turned one of the three red
  (removing the `:no-registered-requirements` rule leaves the design refused by
  `:no-coverage-declared`, at the same phase).

  The other two are NOT caught here, and naming them is more useful than
  implying the table is complete:

    * reversing the two clauses of `phase/of-verdict` — the governor does not
      emit a verdict that is both hard and escalating, so the ordering has no
      observable effect on any scenario. `sysanalysis.phase-test` covers it.
    * unchaining `ledger/entry` (always hashing against prev 0) — almost every
      scenario here runs the graph once, and a single-entry ledger has prev 0
      legitimately. `sysanalysis.ledger-test` covers it, and
      `actor-test/the-ledger-it-leaves-behind-verifies` runs the graph twice on
      one store, which is what makes the break observable.

  Every scenario marked `pre-change` below is one of the refusals measured as
  MISSING on the pre-change tree — see the docstrings of
  `sysanalysis.operation` and `sysanalysis.facts` for those measurements. This
  table is the standing evidence that they are refusals now."
  (:require [sysanalysis.actor :as actor]
            [sysanalysis.advisor :as advisor]
            [sysanalysis.ledger :as led]
            [sysanalysis.phase :as phase]
            [sysanalysis.store :as store]))

(def registered-client
  {:client-id "sim-client-1" :name "Awai Community Systems Co-op"})

(def other-client
  {:client-id "sim-client-2" :name "Another Operator"})

(def registered-requirements
  [{:req-id "R-PERSIST" :client-id "sim-client-1"
    :text "orders persist across a restart"}
   {:req-id "R-AUDIT" :client-id "sim-client-1"
    :text "the audit trail is append-only"}])

(def registered-components
  [{:comp-id "C-DB" :client-id "sim-client-1" :name "order database"}
   {:comp-id "C-LEDGER" :client-id "sim-client-1" :name "audit ledger"}])

(def foreign-component
  "Registered, but to the OTHER client. Citing it is a basis violation, not a
  missing component."
  {:comp-id "C-FOREIGN" :client-id "sim-client-2" :name "someone else's queue"})

(def total-coverage
  "Both registered requirements covered by registered components of this
  client — the only admissible design shape."
  {"R-PERSIST" ["C-DB"] "R-AUDIT" ["C-LEDGER"]})

(defn- tweaking-advisor
  "An advisor that proposes as the mock does, then applies `f` to the proposal.
  Used to reach proposal shapes a well-formed request cannot produce — an
  unusable confidence, a direct write effect."
  [f]
  (let [inner (advisor/mock-advisor)]
    (reify advisor/Advisor
      (-advise [_ store request] (f (advisor/-advise inner store request))))))

(def scenarios
  "Each entry: the request, the phase it must reach, and why.

  `:expect` is the phase, not merely 'refused', so a scenario that starts
  holding for the wrong reason, or that escalates where it should hold, is a
  mismatch rather than a pass.

  `:because` is the violation rule that must appear in the verdict. Without
  it a scenario passes when the graph holds for any reason at all, which is the
  shape where a check stops discriminating without turning red.

  `:clients`, `:requirements` and `:components` override the default
  registration for scenarios about the registration itself."
  [{:name :clean-design-proposal
    :request {:client-id "sim-client-1" :op :propose-design :coverage total-coverage}
    :expect :commit
    :clean? true
    :why "total coverage over registered components is admissible"}

   {:name :propose-design-uncovered-requirement
    :request {:client-id "sim-client-1" :op :propose-design
              :coverage {"R-PERSIST" ["C-DB"]}}
    :because :uncovered-requirement
    :expect :hold
    :why "the original totality invariant, still enforced"}

   {:name :propose-design-empty-component-set
    :request {:client-id "sim-client-1" :op :propose-design
              :coverage {"R-PERSIST" ["C-DB"] "R-AUDIT" []}}
    :because :uncovered-requirement
    :expect :hold
    :why "a requirement named with nothing behind it is uncovered"}

   {:name :approve-cutover-uncovered-requirement
    :request {:client-id "sim-client-1" :op :approve-cutover :coverage {}}
    :because :uncovered-requirement
    :expect :hold
    :why "pre-change: escalated to a human with an EMPTY violation list on the op that switches production"}

   {:name :approve-cutover-foreign-component
    :request {:client-id "sim-client-1" :op :approve-cutover
              :coverage {"R-PERSIST" ["C-FOREIGN"] "R-AUDIT" ["C-LEDGER"]}}
    :because :comp-wrong-client
    :expect :hold
    :why "pre-change: a cutover citing another client's component escalated clean"}

   {:name :approve-cutover-invented-requirement
    :request {:client-id "sim-client-1" :op :approve-cutover
              :coverage (assoc total-coverage "R-GHOST" ["C-DB"])}
    :because :unknown-requirement
    :expect :hold
    :why "pre-change: a cutover citing an unregistered requirement escalated clean"}

   {:name :approve-cutover-clean
    :request {:client-id "sim-client-1" :op :approve-cutover :coverage total-coverage}
    :expect :request-approval
    ;; An escalation carries no violation by construction: the governor
    ;; computes :escalate? as (and (not hard?) ...). `:clean?` asserts the
    ;; violation list is EMPTY, which is the dual of :because and the thing
    ;; that was false on the pre-change tree for this very request.
    :clean? true
    :why "a production cutover is always human sign-off — but now checked before it is asked"}

   {:name :reserved-drop-production-system
    :request {:client-id "sim-client-1" :op :drop-production-system :coverage total-coverage}
    :because :reserved-operation
    :expect :hold
    :why "pre-change {:ok? true}: a production shutdown is authority this cognitive actor does not hold"}

   {:name :reserved-grant-system-access
    :request {:client-id "sim-client-1" :op :grant-system-access :coverage total-coverage}
    :because :reserved-operation
    :expect :hold
    :why "pre-change {:ok? true}: an access grant is the system owner's decision"}

   {:name :reserved-disable-audit-ledger
    :request {:client-id "sim-client-1" :op :disable-audit-ledger :coverage total-coverage}
    :because :reserved-operation
    :expect :hold
    :why "pre-change {:ok? true}: removing the audit trail removes the evidence governance rests on"}

   {:name :reserved-sign-client-contract
    :request {:client-id "sim-client-1" :op :sign-client-contract :coverage total-coverage}
    :because :reserved-operation
    :expect :hold
    :why "pre-change {:ok? true}: proposing and signing are different authorities"}

   {:name :undeclared-operation
    :request {:client-id "sim-client-1" :op :rebuild-everything :coverage total-coverage}
    :because :undeclared-operation
    :expect :hold
    :why "pre-change {:ok? true}: an op outside the declared vocabulary cannot be governed"}

   {:name :nil-operation
    :request {:client-id "sim-client-1" :op nil :coverage total-coverage}
    :because :undeclared-operation
    :expect :hold
    :why "pre-change {:ok? true}: a proposal with no op at all"}

   {:name :unregistered-client
    :request {:client-id "sim-client-9" :op :propose-design :coverage total-coverage}
    :because :no-client
    :expect :hold
    :why "client provenance"}

   {:name :client-record-under-nil-key
    :clients [{}]
    :request {:op :propose-design :coverage {}}
    :because :unidentified-client
    :expect :hold
    :why "pre-change {:ok? true}: the empty client record registered under nil answered a request with no client-id"}

   {:name :no-registered-requirements
    :requirements []
    :request {:client-id "sim-client-1" :op :propose-design :coverage {}}
    :because :no-registered-requirements
    :expect :hold
    :why "pre-change {:ok? true}: totality is a set difference, so an empty requirement set satisfies it vacuously"}

   {:name :uncitable-registered-requirement
    :requirements [{:client-id "sim-client-1" :text "no req-id at all"}]
    :request {:client-id "sim-client-1" :op :propose-design :coverage {nil ["C-DB"]}}
    :because :uncitable-registered-requirement
    :expect :hold
    :why "pre-change {:ok? true}: a requirement with no id was 'covered' by a nil coverage key"}

   {:name :uncitable-component-reference
    :components [{:client-id "sim-client-1" :name "nameless"}]
    :request {:client-id "sim-client-1" :op :propose-design
              :coverage {"R-PERSIST" [nil] "R-AUDIT" [nil]}}
    :because :unreadable-component-refs
    :expect :hold
    :why "pre-change {:ok? true}: a component with no comp-id was cited as nil and looked up cleanly"}

   {:name :coverage-is-not-a-map
    :request {:client-id "sim-client-1" :op :propose-design :coverage ["R-PERSIST"]}
    :because :unreadable-coverage
    :expect :hold
    :why "pre-change this THREW (ISeq from Character) rather than refusing"}

   {:name :coverage-value-is-a-bare-string
    :request {:client-id "sim-client-1" :op :propose-design
              :coverage {"R-PERSIST" "C-DB" "R-AUDIT" "C-LEDGER"}}
    :because :unreadable-component-refs
    :expect :hold
    :why "pre-change the governor iterated the CHARACTERS of the id and reported each as a missing component"}

   {:name :unusable-confidence-out-of-range
    :request {:client-id "sim-client-1" :op :propose-design :coverage total-coverage}
    :tweak #(assoc % :confidence 99.0)
    :because :unusable-confidence
    :expect :hold
    :why "pre-change {:ok? true}: a confidence outside [0,1] buys the advisor out of escalation"}

   {:name :unusable-confidence-non-numeric
    :request {:client-id "sim-client-1" :op :propose-design :coverage total-coverage}
    :tweak #(assoc % :confidence "high")
    :because :unusable-confidence
    :expect :hold
    :why "pre-change this threw on :clj and ADMITTED on :cljs — same tree, opposite outcomes per host"}

   {:name :low-confidence
    :request {:client-id "sim-client-1" :op :propose-design :coverage total-coverage}
    :tweak #(assoc % :confidence 0.1)
    :expect :request-approval
    :clean? true
    :why "below the confidence floor, a human decides"}

   {:name :direct-write-effect
    :request {:client-id "sim-client-1" :op :propose-design :coverage total-coverage}
    :tweak #(assoc % :effect :write)
    :because :no-actuation
    :expect :hold
    :why "the advisor may only propose; a direct write is never admitted"}])

(defn- seeded-store [scenario]
  (let [st (store/mem-store)]
    (doseq [c (concat [registered-client other-client] (:clients scenario))]
      (store/register-client! st c))
    (doseq [r (get scenario :requirements registered-requirements)]
      (store/register-requirement! st r))
    (doseq [c (concat (get scenario :components registered-components)
                      [foreign-component])]
      (store/register-component! st c))
    st))

(defn- run-one [scenario]
  (let [st (seeded-store scenario)
        graph (actor/build-graph
               (cond-> {:store st}
                 (:tweak scenario) (assoc :advisor (tweaking-advisor (:tweak scenario)))))
        thread (str "sim-" (name (:name scenario)))
        result (actor/run-request! graph (:request scenario) {} thread)
        state (:state result)
        actual (or (:disposition state)
                   ;; A run that never reached :decide produced no phase at
                   ;; all; report that rather than defaulting it to a phase,
                   ;; which would make an unrun scenario look like a verdict.
                   :no-phase)
        rules (into #{} (map :rule) (:violations (:verdict state)))
        ;; A scenario with neither :because nor :clean? asserts nothing about
        ;; WHY, so it is reported as unreasoned rather than silently passing.
        reasoned? (or (contains? scenario :because) (:clean? scenario))
        because-ok? (cond
                      (:because scenario) (contains? rules (:because scenario))
                      (:clean? scenario)  (empty? rules)
                      :else false)]
    {:name (:name scenario)
     :expect (:expect scenario)
     :actual actual
     :why (:why scenario)
     :status (:status result)
     :because (:because scenario)
     :rules rules
     :reasoned? reasoned?
     :because-ok? because-ok?
     :match? (and (= actual (:expect scenario)) because-ok?)
     :phase-match? (= actual (:expect scenario))
     :refusal? (and (not= actual :no-phase) (phase/refusal? actual))
     :wrote? (pos? (count (store/records-of st (:client-id (:request scenario)))))
     :ledger-verify (led/verify (store/ledger st))}))

(defn run
  "Run every scenario. Returns
  `{:results [..] :refusals n :mismatches [..] :ledger-breaks [..] :ok? bool}`.

  `:ok?` requires five things: every scenario reached its expected phase FOR
  THE REASON IT NAMES, every scenario names a reason at all, no refusal wrote a
  record anyway, every ledger left behind verifies, and at least one refusal was
  demonstrated."
  []
  (let [results (mapv run-one scenarios)
        refusals (count (filter :refusal? results))
        mismatches (filterv (complement :match?) results)
        unreasoned (filterv (complement :reasoned?) results)
        ;; A refusal that still wrote a record is the worst outcome available
        ;; and would otherwise hide inside a matching phase.
        wrote-anyway (filterv #(and (:refusal? %) (:wrote? %)) results)
        ledger-breaks (filterv #(not (:ok? (:ledger-verify %))) results)]
    {:results results
     :refusals refusals
     :mismatches mismatches
     :unreasoned unreasoned
     :wrote-anyway wrote-anyway
     :ledger-breaks ledger-breaks
     :ok? (and (empty? mismatches)
               (empty? unreasoned)
               (empty? wrote-anyway)
               (empty? ledger-breaks)
               (pos? refusals))}))

(defn report
  "Human-readable run report. Pure: takes the result of `run`."
  [{:keys [results refusals mismatches unreasoned wrote-anyway ledger-breaks ok?]}]
  (str
   "sysanalysis.sim — governed scenario run\n"
   (apply str
          (for [r results]
            (str "  " (if (:match? r) "ok  " "BAD ")
                 (name (:name r))
                 " expect=" (name (:expect r))
                 " actual=" (name (:actual r))
                 (when (:refusal? r) " [refused]")
                 (cond
                   (not (:reasoned? r)) " NO-REASON-DECLARED"
                   (:because-ok? r) (if (:because r)
                                      (str " because=" (name (:because r)))
                                      " clean")
                   :else (str " WRONG-REASON want=" (pr-str (:because r))
                              " got=" (pr-str (:rules r))))
                 "\n")))
   "  scenarios=" (count results)
   " refusals=" refusals
   " mismatches=" (count mismatches)
   " unreasoned=" (count unreasoned)
   " wrote-anyway=" (count wrote-anyway)
   " ledger-breaks=" (count ledger-breaks)
   "\n"
   (cond
     (zero? refusals)
     "  REFUSING TO REPORT A PASS: the scenario table demonstrated no refusal.\n"
     ok? "  PASS\n"
     :else "  FAIL\n")))

#?(:clj
   (defn -main [& _]
     (let [r (run)]
       (print (report r))
       (flush)
       (System/exit (if (:ok? r) 0 1))))
   :cljs
   (defn -main [& _]
     (throw (ex-info "sysanalysis.sim/-main is :clj-only (process exit codes are a host concern); call `run` and inspect the result instead" {}))))
