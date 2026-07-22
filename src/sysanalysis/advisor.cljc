(ns sysanalysis.advisor
  "SystemsAnalysisAdvisor — proposes a systems-analysis operation
  (propose a design, approve a cutover) for a registered organization.
  Swappable mock/llm; the advisor ONLY proposes — `sysanalysis.governor`
  checks requirement-coverage totality independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :propose-design|:approve-cutover
               :effect :propose :coverage {req-id [comp-id ...]}
               :stake kw :confidence n :rationale str}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake coverage] :as request}]
  {:op op
   :effect :propose
   :coverage coverage
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a systems analysis advisor. Given a request, propose an
   :op and a :coverage map from requirement ids to component ids, an
   honest :confidence and a :stake. Never leave a requirement
   uncovered — the governor checks coverage totality against the
   registered requirement set.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
