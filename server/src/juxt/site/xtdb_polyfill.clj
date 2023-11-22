;  Copyright © 2023, JUXT LTD.

(ns juxt.site.xtdb-polyfill
  (:refer-clojure :exclude [sync])
  (:require [xtdb.node :as xt-node]
            [xtdb.api :as xt2])
  (:import (java.util Iterator)
           (java.io Closeable)))

(defn db [node & [xt1-tx]] {:node node, :basis nil})

(defn q [db q & args]
  (xt2/q (:node db) (into [q] args)))

(defn q2-for-db [db]
  (fn q2 [& args]
    (apply xt2/q (:node db) args)))

(def entity-query
  '{:find [r]
    :in [id]
    :where [($ :site [{:xt/id id, :xt/* r}])]})

(defn entity [db id]
  (when (some? id)
    (let [{:keys [node]} db]
      (:r (first (xt2/q node [entity-query id]))))))

(defn entity-for-q [q id]
  (when (some? id)
    (:r (first (q [entity-query id])))))

(defn submit-tx [node & args]
  (try
    (apply xt2/submit-tx node args)
    (catch Throwable e
      ()
      (throw e)
      )))

(defn document-store [node])

(defn fetch-docs [document-store doc-ids])

(defn open-tx-log [node & args]
  (reify Closeable
    (close [_])
    Iterator
    (hasNext [_] false)
    (next [_])))

(def crux->xt identity)

(defn pull [db & args])

(defn pull-many [db & args])

(defn await-tx [node tx] tx)

(defn tx-committed? [node tx] true)

;; use in do-operation
(defn indexing-tx [xt-ctx])

(defn db-basis [db] (:basis db))

;; used for http basic auth subjects
;; who are generated per request and not saved
(defn with-tx [db tx] db)

;; used for valid-time site repl function
;; we can bind vt with a query
(defn entity-tx [db id])

;; used for repl import-resources call to block until indexed
;; not sure if matters
(defn sync [node])

(defn start-node [opts] (xt-node/start-node opts))
