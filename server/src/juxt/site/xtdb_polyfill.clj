;  Copyright © 2022, JUXT LTD.

(ns juxt.site.xtdb-polyfill
  (:refer-clojure :exclude [sync])
  (:require [xtdb.node :as xt-node]
            [xtdb.api :as xt2])
  (:import (java.util Iterator)
           (java.io Closeable)))

(defn db [node] {:node node, :basis nil})

(defn q [db q & args]
  (xt2/q (:node db) (into [q] args)))

(defn entity [db id]
  (let [{:keys [node]} db
        query '{:find [r]
                :in [id]
                :where [($ :site [{:xt/id id, :xt/* r}])]}]
    (:r (first (xt2/q node [query id])))))

(defn submit-tx [node & args] (apply xt2/submit-tx node args))

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

(defn await-tx [node tx])

(defn tx-committed? [node tx])

(defn indexing-tx [xt-ctx])

(defn db-basis [db] (:basis db))

;; used for http basic auth subjects
;; who are generated per request and not saved
(defn with-tx [db tx] db)

(defn entity-tx [db id])

(defn sync [node])

(defn start-node [opts] (xt-node/start-node opts))
