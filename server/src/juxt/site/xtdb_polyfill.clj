;  Copyright © 2022, JUXT LTD.

(ns juxt.site.xtdb-polyfill
  (:refer-clojure :exclude [sync])
  (:import (java.util Iterator)
           (java.io Closeable)))

(defn db [node] {:node node, :basis nil})

(defn q [db q & args]
  (throw (ex-info "Query not implemented" {})))

(defn entity [db id] nil)

(defn submit-tx [node & args] nil)

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

(defn with-tx [db tx] db)

(defn entity-history [db id & args])

(defn entity-tx [db id])

(defn sync [node])
