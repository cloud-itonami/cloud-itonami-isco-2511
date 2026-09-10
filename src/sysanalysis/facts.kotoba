(ns sysanalysis.facts
  "Well-formedness of the values the ISCO-08 2511 community systems-analysis
  actor governs: the client record, the registered requirements, and the
  proposal envelope.

  Runtime: portable `.cljc` (pure predicates, no host interop). Deliberately
  no `clojure.string` dependency — `blank?` is spelled out below so this
  namespace adds no coordinate to `deps.edn`.

  Why this namespace exists — five measurements on the pre-change tree, all
  against a registered client `c1` with one registered requirement `R1` and
  one registered component `K1`.

  1. Client provenance was written as `(nil? client-record)`. That asks
     whether the store returned something, not whether that something
     identifies a client. Registering the empty map put a record under the key
     `nil`, after which a request carrying no `:client-id` resolved to it:

         (register-client! s {})
         (governor/check {} {} <propose-design, :coverage {}> s)
         => {:ok? true :violations []}

     And seeding a record under the key `\"c1\"` whose own `:client-id` is
     `\"c2\"` admitted a `\"c1\"` request the same way. `nil?` is a fact about
     the store's return value; provenance is a fact about the record. Those are
     different questions, and the second one needs a place to live.

  2. `:confidence` is compared against the governor's floor to decide
     escalation, but nothing constrained it:

         <propose-design, :confidence 99.0>   => {:ok? true}  ; not escalated
         <propose-design, :confidence \"high\"> => ClassCastException

     A missing confidence already reads as 0.0 in the governor and therefore
     escalates, which is the safe direction and is left alone. A present but
     unusable confidence is the unsafe direction: it either buys the advisor
     out of escalation with a number that means nothing, or it crashes. And the
     crash is host-specific — measured on the same tree, `:confidence \"high\"`
     threw on `:clj` and returned `{:ok? true :hard? false :escalate? false}`
     under `:cljs` (nbb), because `(< \"high\" 0.6)` compiles to the JavaScript
     `\"high\" < 0.6`, which is `false`. Same defect, opposite outcomes per
     host.

  3. An unreadable `:coverage` crashed rather than refusing:

         <:coverage [\"R1\"]>        => IllegalArgumentException (ISeq from Character)
         <:coverage {\"R1\" \"K1\"}>   => two :unknown-component violations for \"K\" and \"1\"

     A crash is not a refusal: it fails the request in a shape no caller can
     audit, and it is again host-divergent. The second line is worse than a
     crash — the governor iterated the *characters* of a component id and
     reported each one as an unregistered component, so the refusal was real
     but its stated reason was fiction.

  4. A registration with no id was reachable, and made the coverage relation
     satisfiable by something that is not a requirement:

         (register-requirement! s {:client-id \"c1\" :text \"t\"})  ; no :req-id
         <:coverage {nil [\"K1\"]}>  => {:ok? true :violations []}

         (register-component! s {:client-id \"c1\"})               ; no :comp-id
         <:coverage {\"R1\" [nil]}>  => {:ok? true :violations []}

     Both store under the key `nil` and both look up cleanly, so the totality
     relation was satisfied by a pair of records that name nothing. A
     registered record that cannot be cited is a defect in the registration,
     and is reported as one rather than quietly satisfying the invariant.

  5. A fifth measurement is about absence rather than malformation: a design
     proposed for a client with ZERO registered requirements was admitted
     clean.

         (register-client! s {:client-id \"c1\"})   ; no requirements at all
         <:propose-design, :coverage {}>  => {:ok? true :violations []}

     `uncovered` is a set difference against the registered requirement set, so
     an empty requirement set makes totality vacuously true. For this actor the
     registered requirement set *is* what a design is judged against, so its
     absence is the violation rather than the reason not to check.

  The invariant that binds all of these: **a check is never skipped
  silently.** Where a comparison cannot be made, the reason is recorded here
  as a violation of its own, so an unusable value produces a refusal rather
  than an admission."
  (:require [sysanalysis.operation :as op]))

(defn- blank?
  "True for nil, a non-string, or a string of only whitespace. Spelled out
  rather than pulled from `clojure.string` so this namespace stays
  dependency-free."
  [s]
  (or (not (string? s))
      (every? #(contains? #{\space \tab \newline \return} %) s)))

(defn identified?
  "True when `x` is usable as a citable id — a non-blank string. Requirement
  and component ids are cited by the coverage map, so an id that cannot be
  written down cannot be part of the relation."
  [x]
  (not (blank? x)))

(defn usable-confidence?
  "True for a real number in [0,1]. The bounds exclude the infinities, and
  they exclude NaN too — every comparison against NaN is false, so a NaN
  confidence fails `(<= x 1)` already. No host interop, so `:clj` and `:cljs`
  agree, which is the point: the pre-change comparison did not."
  [x]
  (boolean (and (number? x)
                (>= x 0)
                (<= x 1))))

(defn component-refs?
  "True when a coverage value is readable as a list of citable component ids.
  An EMPTY list qualifies: declaring a requirement with nothing behind it is
  well-formed evidence of an uncovered requirement, and the governor's totality
  check is what refuses it. A string does not qualify — `coll?` is false for
  strings on both hosts, which is what stops the governor iterating an id's
  characters."
  [x]
  (boolean (and (coll? x)
                (every? identified? x))))

(defn vocabulary-violations
  "The op must be declared, and must be one this actor may propose. A reserved
  op is reported separately from an undeclared one: the first is an authority
  boundary, the second is a vocabulary error, and a reviewer needs to be able
  to tell them apart."
  [proposal]
  (let [o (:op proposal)]
    (cond
      (op/supported? o) []

      (op/reserved? o)
      [{:rule :reserved-operation
        :detail (str o " はこの actor の権限外: " (op/reserved-reason o))}]

      :else
      [{:rule :undeclared-operation
        :detail (str (pr-str o) " は sysanalysis.operation/supported に無い"
                     "（宣言されていない語彙は governor が判定できない）")}])))

(defn provenance-violations
  "The store's record must identify the client the request names. Asking only
  whether the store returned something admitted the record registered under
  the key `nil`."
  [request client-record]
  (cond
    (nil? client-record)
    [{:rule :no-client :detail "未登録 client"}]

    (blank? (:client-id client-record))
    [{:rule :unidentified-client
      :detail "client record に :client-id が無い（store が何かを返したことと、それが client を同定することは別）"}]

    (not= (:client-id client-record) (:client-id request))
    [{:rule :client-mismatch
      :detail (str "request の client-id " (pr-str (:client-id request))
                   " と store の record " (pr-str (:client-id client-record)) " が一致しない")}]

    :else []))

(defn coverage-violations
  "The coverage envelope of a design-binding operation must be readable before
  it can be compared. Non-binding operations are exempt by declaration, not by
  omission — but there are none today: both supported ops bind.

  Absent and empty are the same claim — no coverage was declared — so they get
  the same rule. A PRESENT but unreadable `:coverage` is a different defect:
  something was offered as evidence and cannot be read. Collapsing the two
  would let a reviewer read \"unreadable\" when nothing was submitted at all."
  [proposal]
  (let [{:keys [op coverage]} proposal]
    (if-not (op/design-op? op)
      []
      (cond-> []
        (or (nil? coverage) (and (map? coverage) (empty? coverage)))
        (conj {:rule :no-coverage-declared
               :detail (str "設計が被覆表を 1 件も宣言していない（被覆表が"
                            "「要件を検討した」証拠そのものなので、その不在が違反）")})

        (and (some? coverage) (not (map? coverage)))
        (conj {:rule :unreadable-coverage
               :detail (str ":coverage が要件 id → component id の map として読めない: "
                            (pr-str coverage))})

        (and (map? coverage) (seq (remove identified? (keys coverage))))
        (conj {:rule :uncitable-requirement-key
               :detail (str "被覆表に id として書けない要件キーが在る: "
                            (pr-str (vec (remove identified? (keys coverage))))
                            "（nil キーは :req-id 無しで登録された要件と一致してしまう）")})

        (and (map? coverage)
             (seq (remove (comp component-refs? val) coverage)))
        (conj {:rule :unreadable-component-refs
               :detail (str "component id の列として読めない被覆値が在る: "
                            (pr-str (into {} (remove (comp component-refs? val)) coverage))
                            "（文字列を渡すと governor が id の 1 文字ずつを component として数える）")})))))

(defn requirement-record-violations
  "A registered requirement must itself be citable. A requirement stored
  without a `:req-id` lands under the key `nil`, and a coverage entry whose key
  is `nil` then satisfies totality against it — the relation is total over a
  pair of records that name nothing."
  [op reqs]
  (if-not (op/design-op? op)
    []
    (let [bad (remove #(identified? (:req-id %)) reqs)]
      (if (seq bad)
        [{:rule :uncitable-registered-requirement
          :detail (str "登録済み要件に :req-id が無い（" (count bad)
                       " 件）。id の無い要件は被覆表から名指せないので、"
                       "nil キーで「被覆済み」にできてしまう")}]
        []))))

(defn requirement-set-violations
  "A design-binding operation needs a non-empty registered requirement set to
  be judged against. `uncovered` is a set difference, so an empty requirement
  set makes coverage totality vacuously true."
  [op reqs]
  (if-not (op/design-op? op)
    []
    (if (empty? reqs)
      [{:rule :no-registered-requirements
        :detail (str "client に登録済み要件が 1 件も無い。被覆の全域性は差集合なので"
                     "空の要件集合では自明に真になる —— 判定する対象が無いことは"
                     "設計が完全であることではない")}]
      [])))

(defn confidence-violations
  "A present `:confidence` must be a usable number in [0,1]. Absent is left to
  the governor, where it already reads as 0.0 and escalates."
  [proposal]
  (let [c (:confidence proposal)]
    (if (or (nil? c) (usable-confidence? c))
      []
      [{:rule :unusable-confidence
        :detail (str ":confidence " (pr-str c) " は [0,1] の数ではない"
                     "（escalation 判定に使う値なので、意味の無い数で escalation を"
                     "買えてしまう。host によっては例外にも false にもなる）")}])))
