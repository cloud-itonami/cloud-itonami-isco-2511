(ns sysanalysis.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.actor :as actor]
            [sysanalysis.ledger :as ledger]
            [sysanalysis.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-requirement! st {:req-id "REQ-1" :client-id "client-1"
                                     :text "orders persist across restart"})
    (store/register-component! st {:comp-id "C-DB" :client-id "client-1"
                                   :name "order database"})
    st))

(deftest commits-a-totally-covering-design
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :propose-design :stake :low
                 :coverage {"REQ-1" ["C-DB"]}}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-incomplete-design
  (let [st (fresh-store)]
    (store/register-requirement! st {:req-id "REQ-2" :client-id "client-1"
                                     :text "audit log is append-only"})
    (let [graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :propose-design :stake :low
                   :coverage {"REQ-1" ["C-DB"]}}
          result (actor/run-request! graph request {} "thread-2")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest interrupts-then-cuts-over-on-human-approval
  ;; This test previously sent a cutover with NO :coverage and asserted that
  ;; the graph interrupted for a human. It passed because `:approve-cutover`
  ;; was exempt from the coverage checks — the request reached a human while
  ;; REQ-1 had nothing behind it. The coverage is now supplied, because the
  ;; interrupt is for proposals the governor has already checked.
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-cutover :stake :high
                 :coverage {"REQ-1" ["C-DB"]}}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))

(deftest holds-a-cutover-that-cannot-show-total-coverage
  (testing "the graph refuses before the human is asked, not after"
    (let [st (fresh-store)]
      (store/register-requirement! st {:req-id "REQ-2" :client-id "client-1"
                                       :text "audit log is append-only"})
      (let [graph (actor/build-graph {:store st})
            request {:client-id "client-1" :op :approve-cutover :stake :high
                     :coverage {"REQ-1" ["C-DB"]}}
            result (actor/run-request! graph request {} "thread-4")]
        (is (= :hold (:disposition (:state result))))
        (is (empty? (store/records-of st "client-1")))))))

(deftest records-who-approved-each-write
  (testing "a human-approved cutover and an automatic design must be distinguishable in the ledger"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :propose-design
                                 :stake :low :coverage {"REQ-1" ["C-DB"]}}
                          {} "auto-1")
      (actor/run-request! graph {:client-id "client-1" :op :approve-cutover
                                 :stake :high :coverage {"REQ-1" ["C-DB"]}}
                          {} "human-1")
      (actor/approve! graph "human-1")
      (let [entries (filterv #(= :commit (:disposition %)) (store/ledger st))]
        (is (= 2 (count entries)))
        (is (= [:actor :human] (mapv :approved-by entries)))))))

(deftest the-ledger-it-leaves-behind-verifies
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph {:client-id "client-1" :op :propose-design
                               :stake :low :coverage {"REQ-1" ["C-DB"]}}
                        {} "chain-1")
    (actor/run-request! graph {:client-id "client-1" :op :rebuild-everything
                               :stake :low :coverage {"REQ-1" ["C-DB"]}}
                        {} "chain-2")
    (let [l (store/ledger st)]
      (is (= 2 (count l)))
      (is (:ok? (ledger/verify l)))
      (testing "and a reordered ledger does not verify"
        (is (not (:ok? (ledger/verify (vec (reverse l))))))))))
