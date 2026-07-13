(ns sysanalysis.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sysanalysis.actor :as actor]
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
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-cutover :stake :high}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
