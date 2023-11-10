;; Copyright © 2023, JUXT LTD.

(ns juxt.site.test-helpers.xt
  (:require
    [integrant.core :as ig]
    [juxt.site.main :as main]
    [juxt.site.db :as db]
    [juxt.site.xtdb-polyfill :as xt]))

(def ^:dynamic *opts* {})
(def ^:dynamic *xt-node*)

(defmacro with-xt [& body]
  `(with-open [node# (ig/init-key ::db/xt-node *opts*)]
     (binding [*xt-node* node#]
       ~@body)))

(defn xt-fixture [f]
  (with-xt (f)))

(defmacro with-system-xt [& body]
  `(with-open [node# (ig/init-key ::db/xt-node *opts*)]
     (binding [*xt-node* node#
               main/*system* {:juxt.site.db/xt-node node#}]
       ~@body)))

(defn system-xt-fixture [f]
  (with-system-xt (f)))

(defn submit-and-await! [transactions]
  (->>
   (xt/submit-tx *xt-node* transactions)
   (xt/await-tx *xt-node*)))
