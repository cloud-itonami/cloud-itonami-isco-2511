(ns sysanalysis.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.facts :as facts]))

(deftest identified-requires-a-non-blank-string
  (is (facts/identified? "R-1"))
  (is (not (facts/identified? nil)))
  (is (not (facts/identified? "")))
  (is (not (facts/identified? "   ")))
  (testing "a keyword id cannot be written into a coverage map as a string id"
    (is (not (facts/identified? :R-1)))))

(deftest usable-confidence-is-a-number-in-the-unit-interval
  (is (facts/usable-confidence? 0))
  (is (facts/usable-confidence? 1))
  (is (facts/usable-confidence? 0.6))
  (is (not (facts/usable-confidence? 99.0)))
  (is (not (facts/usable-confidence? -0.1)))
  (is (not (facts/usable-confidence? "high")))
  (is (not (facts/usable-confidence? nil)))
  (testing "NaN fails without a host-specific check: every comparison against it is false"
    (is (not (facts/usable-confidence? (/ 0.0 0.0))))))

(deftest component-refs-accepts-an-empty-list-and-rejects-a-string
  (testing "an empty list is well-formed evidence of an uncovered requirement"
    (is (facts/component-refs? []))
    (is (facts/component-refs? ["C-DB"]))
    (is (facts/component-refs? #{"C-DB" "C-LEDGER"})))
  (testing "a string is what made the governor iterate an id's characters"
    (is (not (facts/component-refs? "C-DB"))))
  (is (not (facts/component-refs? [nil])))
  (is (not (facts/component-refs? nil))))

(deftest vocabulary-distinguishes-undeclared-from-reserved
  (is (empty? (facts/vocabulary-violations {:op :propose-design})))
  (is (= [:reserved-operation]
         (mapv :rule (facts/vocabulary-violations {:op :drop-production-system}))))
  (is (= [:undeclared-operation]
         (mapv :rule (facts/vocabulary-violations {:op :rebuild-everything}))))
  (is (= [:undeclared-operation]
         (mapv :rule (facts/vocabulary-violations {:op nil}))))
  (testing "a reserved refusal states the authority it names"
    (is (seq (:detail (first (facts/vocabulary-violations {:op :grant-system-access})))))))

(deftest provenance-asks-about-the-record-not-the-return-value
  (is (empty? (facts/provenance-violations {:client-id "c1"} {:client-id "c1"})))
  (is (= [:no-client] (mapv :rule (facts/provenance-violations {:client-id "c1"} nil))))
  (testing "the empty record stored under the nil key"
    (is (= [:unidentified-client] (mapv :rule (facts/provenance-violations {} {})))))
  (is (= [:client-mismatch]
         (mapv :rule (facts/provenance-violations {:client-id "c1"} {:client-id "c2"})))))

(deftest coverage-violations-separate-absent-from-unreadable
  (let [rules #(set (mapv :rule (facts/coverage-violations
                                 {:op :propose-design :coverage %})))]
    (is (empty? (rules {"R-1" ["C-1"]})))
    (is (contains? (rules nil) :no-coverage-declared))
    (is (contains? (rules {}) :no-coverage-declared))
    (is (contains? (rules ["R-1"]) :unreadable-coverage))
    (is (contains? (rules {nil ["C-1"]}) :uncitable-requirement-key))
    (is (contains? (rules {"R-1" "C-1"}) :unreadable-component-refs))
    (is (contains? (rules {"R-1" [nil]}) :unreadable-component-refs))))

(deftest a-non-binding-op-is-exempt-by-declaration
  (testing "an undeclared op is hard-blocked by the vocabulary check, not by these"
    (is (empty? (facts/coverage-violations {:op :rebuild-everything :coverage nil})))
    (is (empty? (facts/requirement-set-violations :rebuild-everything [])))
    (is (empty? (facts/requirement-record-violations :rebuild-everything [{}])))))

(deftest an-empty-requirement-set-is-the-violation
  (is (= [:no-registered-requirements]
         (mapv :rule (facts/requirement-set-violations :propose-design []))))
  (is (empty? (facts/requirement-set-violations :propose-design [{:req-id "R-1"}]))))

(deftest an-uncitable-registered-requirement-is-reported-once
  (is (empty? (facts/requirement-record-violations :propose-design [{:req-id "R-1"}])))
  (let [v (facts/requirement-record-violations :propose-design
                                               [{:req-id "R-1"} {:text "no id"} {:req-id ""}])]
    (is (= [:uncitable-registered-requirement] (mapv :rule v)))
    (is (re-find #"2 件" (:detail (first v))))))

(deftest confidence-violations-leave-absent-alone
  (testing "absent already reads as 0.0 in the governor and escalates -- the safe direction"
    (is (empty? (facts/confidence-violations {})))
    (is (empty? (facts/confidence-violations {:confidence 0.1}))))
  (is (= [:unusable-confidence] (mapv :rule (facts/confidence-violations {:confidence 99.0}))))
  (is (= [:unusable-confidence] (mapv :rule (facts/confidence-violations {:confidence "high"})))))
