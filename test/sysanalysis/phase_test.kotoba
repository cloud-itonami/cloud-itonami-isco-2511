(ns sysanalysis.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.phase :as phase]))

(deftest routes-each-verdict-shape
  (is (= :commit (phase/of-verdict {:hard? false :escalate? false})))
  (is (= :request-approval (phase/of-verdict {:hard? false :escalate? true})))
  (is (= :hold (phase/of-verdict {:hard? true :escalate? false}))))

(deftest a-verdict-that-is-both-hard-and-escalating-holds
  (testing "escalating it would ask a human to approve what no human may approve"
    ;; The governor does not emit this shape today -- it computes :escalate? as
    ;; (and (not hard?) ...). This is the second of two independent guards, and
    ;; the one that holds for any caller building a verdict by hand.
    (is (= :hold (phase/of-verdict {:hard? true :escalate? true})))))

(deftest only-commit-writes
  (is (phase/writes? :commit))
  (is (not (phase/writes? :hold)))
  (is (not (phase/writes? :request-approval))))

(deftest both-non-writing-phases-are-refusals
  (testing "request-approval is a refusal to act without a human, not an approval-in-waiting"
    (is (phase/refusal? :hold))
    (is (phase/refusal? :request-approval))
    (is (not (phase/refusal? :commit)))))

(deftest an-unknown-phase-claims-no-authority
  (testing "writes?/human-required? default to false, so an unknown phase cannot write"
    (is (not (phase/writes? :no-phase)))
    (is (not (phase/human-required? :no-phase)))
    (is (not (phase/terminal? :no-phase)))))

(deftest only-the-escalated-path-is-a-human-approved-commit
  (is (phase/approved-commit? :request-approval))
  (is (not (phase/approved-commit? :commit)))
  (is (not (phase/approved-commit? nil))))

(deftest the-interrupt-phase-is-not-terminal
  (testing "a thread parked for sign-off must be resumable"
    (is (not (phase/terminal? :request-approval)))
    (is (phase/human-required? :request-approval))
    (is (phase/terminal? :hold))
    (is (phase/terminal? :commit))))
