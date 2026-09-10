(ns sysanalysis.operation-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [sysanalysis.operation :as op]))

(deftest supported-and-reserved-are-disjoint
  (testing "an op cannot be both proposable and an authority boundary"
    (is (empty? (set/intersection (set (keys op/supported))
                                          (set (keys op/reserved)))))))

(deftest every-declared-op-carries-its-properties
  (testing "escalation and design-binding are properties of the operation"
    (doseq [[o m] op/supported]
      (is (contains? m :escalates?) (str o " must declare :escalates?"))
      (is (contains? m :design-op?) (str o " must declare :design-op?"))
      (is (string? (:summary m)) (str o " must carry a summary")))))

(deftest every-reserved-op-says-why
  (testing "a reserved op is declared rather than absent so the refusal can explain itself"
    (doseq [[o m] op/reserved]
      (is (string? (:reason m)) (str o " must carry a reason"))
      (is (seq (:reason m))))))

(deftest an-undeclared-op-is-neither-supported-nor-reserved
  (is (not (op/declared? :rebuild-everything)))
  (is (not (op/declared? nil)))
  (is (not (op/supported? :rebuild-everything)))
  (is (not (op/reserved? :rebuild-everything))))

(deftest the-cutover-is-the-escalating-design-binding-op
  (testing "this coincidence is why phase/of-verdict checks :hard? first"
    (is (op/escalates? :approve-cutover))
    (is (op/design-op? :approve-cutover))))

(deftest a-design-proposal-binds-without-escalating
  (is (op/design-op? :propose-design))
  (is (not (op/escalates? :propose-design))))

(deftest undeclared-ops-are-never-escalating-or-binding
  (testing "a false here is not an admission -- the governor hard-blocks first"
    (is (not (op/escalates? :rebuild-everything)))
    (is (not (op/design-op? :rebuild-everything)))
    (is (not (op/escalates? :drop-production-system)))
    (is (not (op/design-op? :drop-production-system)))))
