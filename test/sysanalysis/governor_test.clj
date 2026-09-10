(ns sysanalysis.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.store :as store]
            [sysanalysis.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-requirement! st {:req-id "REQ-1" :client-id "client-1"
                                     :text "orders persist across restart"})
    (store/register-requirement! st {:req-id "REQ-2" :client-id "client-1"
                                     :text "audit log is append-only"})
    (store/register-component! st {:comp-id "C-DB" :client-id "client-1"
                                   :name "order database"})
    (store/register-component! st {:comp-id "C-LEDGER" :client-id "client-1"
                                   :name "audit ledger"})
    st))

(defn- design [coverage]
  {:op :propose-design :effect :propose :coverage coverage
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-with-total-coverage
  (let [st (fresh-store)
        v (governor/check req {} (design {"REQ-1" ["C-DB"]
                                          "REQ-2" ["C-LEDGER"]}) st)]
    (is (:ok? v))))

(deftest ok-with-multi-component-coverage
  (let [st (fresh-store)
        v (governor/check req {} (design {"REQ-1" ["C-DB" "C-LEDGER"]
                                          "REQ-2" ["C-LEDGER"]}) st)]
    (is (:ok? v))))

(deftest hard-on-uncovered-requirement
  (testing "an uncovered requirement is arithmetic absence, not an open question"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (design {"REQ-1" ["C-DB"]})
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :uncovered-requirement (:rule %)) (:violations v))))))

(deftest hard-on-empty-component-mapping
  (testing "a key with an empty component set is still uncovered"
    (let [st (fresh-store)
          v (governor/check req {} (design {"REQ-1" ["C-DB"]
                                            "REQ-2" []}) st)]
      (is (:hard? v))
      (is (some #(= :uncovered-requirement (:rule %)) (:violations v))))))

(deftest hard-on-invented-requirement
  (let [st (fresh-store)
        v (governor/check req {} (design {"REQ-1" ["C-DB"]
                                          "REQ-2" ["C-LEDGER"]
                                          "REQ-ghost" ["C-DB"]}) st)]
    (is (:hard? v))
    (is (some #(= :unknown-requirement (:rule %)) (:violations v)))))

(deftest hard-on-unknown-component
  (let [st (fresh-store)
        v (governor/check req {} (design {"REQ-1" ["C-ghost"]
                                          "REQ-2" ["C-LEDGER"]}) st)]
    (is (:hard? v))
    (is (some #(= :unknown-component (:rule %)) (:violations v)))))

(deftest hard-on-foreign-component
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (store/register-component! st {:comp-id "C-OTHER" :client-id "client-2"
                                   :name "someone else's queue"})
    (let [v (governor/check req {} (design {"REQ-1" ["C-OTHER"]
                                            "REQ-2" ["C-LEDGER"]}) st)]
      (is (:hard? v))
      (is (some #(= :comp-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (design {}) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (design {"REQ-1" ["C-DB"]
                                                 "REQ-2" ["C-LEDGER"]})
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-cutover-approval
  (testing "a cutover whose coverage is total reaches the human, and only then"
    ;; This test previously passed a cutover with NO :coverage at all and
    ;; asserted that it escalated cleanly. That assertion was the defect, not
    ;; the evidence of correctness: `:approve-cutover` was exempt from the
    ;; coverage checks, so the op that switches production reached a human
    ;; with an empty violation list while two registered requirements had
    ;; nothing behind them. The coverage is now supplied, because a cutover
    ;; that cannot show total coverage must hold rather than ask.
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-cutover :effect :propose
                                    :coverage {"REQ-1" ["C-DB"]
                                               "REQ-2" ["C-LEDGER"]}
                                    :confidence 0.9 :stake :high} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest holds-cutover-with-uncovered-requirement
  (testing "the exemption the previous version of the test above encoded"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-cutover :effect :propose
                                    :coverage {"REQ-1" ["C-DB"]}
                                    :confidence 0.9 :stake :high} st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :uncovered-requirement (:rule %)) (:violations v))))))

(deftest holds-cutover-citing-a-foreign-component
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (store/register-component! st {:comp-id "C-OTHER" :client-id "client-2"
                                   :name "someone else's queue"})
    (let [v (governor/check req {} {:op :approve-cutover :effect :propose
                                    :coverage {"REQ-1" ["C-OTHER"]
                                               "REQ-2" ["C-LEDGER"]}
                                    :confidence 0.9 :stake :high} st)]
      (is (:hard? v))
      (is (some #(= :comp-wrong-client (:rule %)) (:violations v))))))

(deftest holds-cutover-citing-an-invented-requirement
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-cutover :effect :propose
                                  :coverage {"REQ-1" ["C-DB"]
                                             "REQ-2" ["C-LEDGER"]
                                             "REQ-ghost" ["C-DB"]}
                                  :confidence 0.9 :stake :high} st)]
    (is (:hard? v))
    (is (some #(= :unknown-requirement (:rule %)) (:violations v)))))

(deftest holds-an-undeclared-operation
  (testing "the governor bound one op name and admitted everything else"
    (let [st (fresh-store)
          v (governor/check req {} {:op :rebuild-everything :effect :propose
                                    :coverage {"REQ-1" ["C-DB"]
                                               "REQ-2" ["C-LEDGER"]}
                                    :confidence 0.95} st)]
      (is (:hard? v))
      (is (some #(= :undeclared-operation (:rule %)) (:violations v))))))

(deftest holds-a-nil-operation
  (let [st (fresh-store)
        v (governor/check req {} {:op nil :effect :propose
                                  :coverage {"REQ-1" ["C-DB"]
                                             "REQ-2" ["C-LEDGER"]}
                                  :confidence 0.95} st)]
    (is (:hard? v))
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))))

(deftest holds-a-reserved-operation-rather-than-escalating-it
  (testing "a reserved op is an authority boundary, so no human may approve it"
    (let [st (fresh-store)
          v (governor/check req {} {:op :drop-production-system :effect :propose
                                    :coverage {"REQ-1" ["C-DB"]
                                               "REQ-2" ["C-LEDGER"]}
                                    :confidence 0.95} st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :reserved-operation (:rule %)) (:violations v))))))

(deftest holds-an-unidentified-client-record
  (testing "the store returning something is not the same as it identifying a client"
    (let [st (store/mem-store)]
      (store/register-client! st {})
      (let [v (governor/check {} {} (design {}) st)]
        (is (:hard? v))
        (is (some #{:unidentified-client :no-client}
                  (map :rule (:violations v))))))))

(deftest holds-a-client-record-naming-a-different-client
  (let [st (store/mem-store {:clients {"client-1" {:client-id "client-2" :name "Other"}}
                             :requirements {} :components {} :records [] :ledger []})
        v (governor/check req {} (design {"REQ-1" ["C-DB"]}) st)]
    (is (:hard? v))
    (is (some #(= :client-mismatch (:rule %)) (:violations v)))))

(deftest holds-a-design-for-a-client-with-no-registered-requirements
  (testing "totality is a set difference, so an empty requirement set satisfies it vacuously"
    (let [st (store/mem-store)]
      (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
      (let [v (governor/check req {} (design {}) st)]
        (is (:hard? v))
        (is (some #(= :no-registered-requirements (:rule %)) (:violations v)))))))

(deftest holds-an-uncitable-registered-requirement
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-requirement! st {:client-id "client-1" :text "no req-id"})
    (store/register-component! st {:comp-id "C-DB" :client-id "client-1"})
    (let [v (governor/check req {} (design {nil ["C-DB"]}) st)]
      (is (:hard? v))
      (is (some #(= :uncitable-registered-requirement (:rule %)) (:violations v))))))

(deftest refuses-unreadable-coverage-instead-of-throwing
  (testing "a crash is not a refusal: it fails the request in a shape no caller can audit"
    (let [st (fresh-store)
          v (governor/check req {} (design ["REQ-1"]) st)]
      (is (:hard? v))
      (is (some #(= :unreadable-coverage (:rule %)) (:violations v))))))

(deftest refuses-a-bare-string-component-reference
  (testing "the pre-change governor iterated the CHARACTERS of the id"
    (let [st (fresh-store)
          v (governor/check req {} (design {"REQ-1" "C-DB" "REQ-2" "C-LEDGER"}) st)]
      (is (:hard? v))
      (is (some #(= :unreadable-component-refs (:rule %)) (:violations v)))
      (is (not-any? #(= :unknown-component (:rule %)) (:violations v))))))

(deftest refuses-an-out-of-range-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (design {"REQ-1" ["C-DB"]
                                                 "REQ-2" ["C-LEDGER"]})
                                        :confidence 99.0) st)]
    (is (:hard? v))
    (is (some #(= :unusable-confidence (:rule %)) (:violations v)))))

(deftest refuses-a-non-numeric-confidence-on-every-host
  (testing "pre-change this threw on :clj and ADMITTED on :cljs"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (design {"REQ-1" ["C-DB"]
                                                   "REQ-2" ["C-LEDGER"]})
                                          :confidence "high") st)]
      (is (:hard? v))
      (is (= 0.0 (:confidence v)))
      (is (some #(= :unusable-confidence (:rule %)) (:violations v))))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (design {"REQ-1" ["C-DB"]
                                                 "REQ-2" ["C-LEDGER"]})
                                        :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
