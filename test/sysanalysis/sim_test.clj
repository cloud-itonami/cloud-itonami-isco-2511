(ns sysanalysis.sim-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.sim :as sim]))

(deftest the-scenario-table-demonstrates-refusals
  (let [r (sim/run)]
    (is (empty? (:mismatches r))
        (str "scenarios reached the wrong phase: "
             (pr-str (mapv (juxt :name :expect :actual) (:mismatches r)))))
    (is (empty? (:wrote-anyway r))
        "a refused scenario wrote a record anyway")
    (is (empty? (:ledger-breaks r))
        "a run left behind a ledger that does not verify")
    (is (pos? (:refusals r)))
    (is (:ok? r))))

(deftest the-table-admits-at-least-one-proposal
  (testing "a harness that refused everything would also demonstrate nothing"
    (let [r (sim/run)]
      (is (some #(= :commit (:actual %)) (:results r))))))

(deftest a-zero-refusal-run-refuses-to-report-a-pass
  (testing "the report says so in words, not only in the exit code"
    (let [empty-run {:results [] :refusals 0 :mismatches [] :wrote-anyway []
                     :ledger-breaks [] :ok? false}]
      (is (re-find #"REFUSING TO REPORT A PASS" (sim/report empty-run)))
      (is (not (re-find #"PASS\n$" (sim/report empty-run)))))))

(deftest every-scenario-declares-why-it-is-there
  (doseq [s sim/scenarios]
    (is (keyword? (:name s)))
    (is (contains? #{:commit :hold :request-approval} (:expect s))
        (str (:name s) " must expect a declared phase"))
    (is (string? (:why s)) (str (:name s) " must say why"))))
