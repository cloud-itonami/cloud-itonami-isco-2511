(ns sysanalysis.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.ledger :as ledger]))

(deftest an-empty-ledger-verifies
  (is (:ok? (ledger/verify [])))
  (is (= 0 (:length (ledger/verify [])))))

(deftest appending-chains-each-entry-to-the-last
  (let [l (-> [] (ledger/append {:disposition :commit}) (ledger/append {:disposition :hold}))]
    (is (= [0 1] (mapv :ledger/seq l)))
    (is (= 0 (:ledger/prev (first l))))
    (is (= (:ledger/hash (first l)) (:ledger/prev (second l))))
    (is (:ok? (ledger/verify l)))))

(deftest a-reordered-ledger-does-not-verify
  (let [l (-> [] (ledger/append {:n 1}) (ledger/append {:n 2}) (ledger/append {:n 3}))
        v (ledger/verify (vec (reverse l)))]
    (is (not (:ok? v)))
    (is (= :seq-mismatch (:reason v)))))

(deftest an-edited-entry-does-not-verify
  (let [l (-> [] (ledger/append {:disposition :hold}) (ledger/append {:disposition :commit}))
        tampered (assoc-in l [1 :disposition] :hold)
        v (ledger/verify tampered)]
    (is (not (:ok? v)))
    (is (= 1 (:broken-at v)))
    (is (= :hash-mismatch (:reason v)))))

(deftest a-dropped-middle-entry-does-not-verify
  (let [l (-> [] (ledger/append {:n 1}) (ledger/append {:n 2}) (ledger/append {:n 3}))
        v (ledger/verify [(nth l 0) (nth l 2)])]
    (is (not (:ok? v)))
    (is (= 1 (:broken-at v)))))

(deftest truncation-at-the-tail-is-NOT-detected
  (testing "stated rather than hidden: a chain cannot detect entries it never saw"
    ;; Detecting truncation needs an external anchor (a signed head), which
    ;; this in-memory store does not have. verify claims only what it can show.
    (let [l (-> [] (ledger/append {:n 1}) (ledger/append {:n 2}) (ledger/append {:n 3}))]
      (is (:ok? (ledger/verify (subvec l 0 2))))
      (is (= 2 (:length (ledger/verify (subvec l 0 2))))))))

(deftest the-hash-commits-to-position-not-only-to-content
  (testing "two identical entries at different positions hash differently"
    (let [l (-> [] (ledger/append {:same :content}) (ledger/append {:same :content}))]
      (is (not= (:ledger/hash (first l)) (:ledger/hash (second l)))))))

(deftest the-hash-is-in-range-and-deterministic
  (is (= (ledger/chain-hash 0 {:a 1}) (ledger/chain-hash 0 {:a 1})))
  (is (not= (ledger/chain-hash 0 {:a 1}) (ledger/chain-hash 1 {:a 1})))
  (is (<= 0 (ledger/chain-hash 0 {:a 1}) 2147483646)))

(deftest commit-entries-record-approval-provenance-in-the-same-shape
  (testing "an absent field must not be mistakable for an unaudited one"
    (let [auto (ledger/commit-entry {:op :propose-design} :actor)
          human (ledger/commit-entry {:op :approve-cutover} :human)]
      (is (= :actor (:approved-by auto)))
      (is (= :human (:approved-by human)))
      (is (= (set (keys auto)) (set (keys human)))))))

(deftest hold-entries-carry-the-violations
  (testing "the ledger explains the refusal without needing the run that produced it"
    (let [e (ledger/hold-entry {:hard? true :violations [{:rule :uncovered-requirement}]})]
      (is (= :hold (:disposition e)))
      (is (= :none (:approved-by e)))
      (is (= [{:rule :uncovered-requirement}] (:violations (:verdict e)))))))

(deftest summary-names-the-approver-of-every-line
  (let [l (-> [] (ledger/append (ledger/commit-entry {:op :propose-design} :actor))
              (ledger/append (ledger/hold-entry {:hard? true})))
        s (ledger/summary l)]
    (is (re-find #"0 commit approved-by=actor" s))
    (is (re-find #"1 hold approved-by=none" s))))
