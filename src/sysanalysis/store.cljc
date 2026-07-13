(ns sysanalysis.store
  "SSoT for the ISCO-08 2511 community systems-analysis actor (itonami
  actor pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client      — a registered organization (:client-id, :name)
    requirement — a registered system requirement {:req-id :client-id
                  :text}. The requirement set is the SSoT a design is
                  judged against.
    component   — a registered system component {:comp-id :client-id
                  :name}. Designs may only cite registered components.
    record      — a committed operating record (proposed design,
                  approved cutover) — written ONLY via commit-record!.
    ledger      — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (requirements-of [s client-id])
  (component [s comp-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-requirement! [s r])
  (register-component! [s c])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (requirements-of [_ client-id]
    (filter #(= client-id (:client-id %)) (vals (:requirements @a))))
  (component [_ comp-id] (get-in @a [:components comp-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-requirement! [s r]
    (swap! a assoc-in [:requirements (:req-id r)] r) s)
  (register-component! [s c]
    (swap! a assoc-in [:components (:comp-id c)] c) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :requirements {}
                                    :components {} :records [] :ledger []}
                                   seed)))))
