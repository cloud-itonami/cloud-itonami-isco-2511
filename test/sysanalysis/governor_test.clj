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
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-cutover :effect :propose
                                  :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (design {"REQ-1" ["C-DB"]
                                                 "REQ-2" ["C-LEDGER"]})
                                        :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
