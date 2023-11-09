;; Copyright © 2021, JUXT LTD.

(ns juxt.site.db
  (:require
    [juxt.site.operations :as operations]
    [juxt.site.xtdb-polyfill :as xt]
    [xtdb.node :as xt-node]
    [integrant.core :as ig]
    [clojure.tools.logging :as log]))

(defmethod ig/init-key ::xt-node [_ xtdb-opts]
  (log/info "Starting XT node")
  (doto (xt-node/start-node
          (merge {:xtdb/indexer {:sci-opts operations/operation-sci-opts}}
                 xtdb-opts))
    (xt/submit-tx [operations/do-operation-in-tx-fn-tx-op])))

(defmethod ig/halt-key! ::xt-node [_ node]
  (.close node)
  (log/info "Closed XT node"))

(comment

  (with-open [node (ig/init-key ::xt-node {})]
    (.-sci_opts (:indexer node))

    )

  )
