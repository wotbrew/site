;; Copyright © 2023, JUXT LTD.

(ns juxt.site.xtdb2-upgrade-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [juxt.site.main :as main]
            [java-http-clj.core :as http]
            [juxt.site.operations :as operations]
            [juxt.site.xtdb-polyfill :as xt]
            [xtdb.api :as xt2]
            [juxt.site.test-helpers.local-files-util :refer [install-bundles!]]
            [juxt.site.test-helpers.oauth :refer [RESOURCE_SERVER] :as oauth]))

(defn until-true [ms f]
  (loop [wait-until (+ (System/currentTimeMillis) ms)
         ret (f)]
    (cond
      ret ret
      (< (System/currentTimeMillis) wait-until) (recur wait-until (f))
      :else ret)))

(defonce systems (atom []))

(defn run-system []
  (let [system-config (main/system-config)
        system (ig/init system-config)]
    (swap! systems conj system)
    {:config system-config
     :system system}))

(defn halt-systems []
  (run! ig/halt! @systems)
  (reset! systems []))

(defn each-fixture [f]
  (System/setProperty "site.config" "etc/config/local-development.edn")
  (halt-systems)
  (try
    (f)
    (finally
      (halt-systems))))

(use-fixtures :each #'each-fixture)

(deftest can-load-all-sources-test
  (doseq [file (file-seq (io/file "src"))
          :when (and (not (.isDirectory file))
                     (str/ends-with? (str file) ".clj"))]
    (load (-> (str file)
              (str/replace #"^src/juxt/site/" "")
              (str/replace #"\.clj$" "")))))

(deftest main-zero-args-test
  (require 'juxt.site.main :reload)
  (when main/*system* (ig/halt! main/*system*))
  (let [run-main
        (fn []
          (try
            (main/-main)
            (catch Throwable t
              (log/error t)
              (throw t))))
        main-thread (Thread. ^Runnable run-main)]
    (.start main-thread)
    (is (until-true 10000 #(some? main/*system*)))
    (.interrupt main-thread)
    (.join main-thread 500)
    (is (not (.isAlive main-thread)))))

(defn listener-port [config]
  (-> config (get [:juxt.site.listener/listener :juxt.site.listener/secondary-listener]) :juxt.site/port))

(defn get-uri [config & parts]
   (format "http://localhost:%s%s" (listener-port config)
          (if (seq parts)
            (str/join "/" (cons "" parts))
            "")))

(defn GET [config path] (http/send (get-uri config path)))

(deftest healthcheck-test
  (let [{:keys [config]} (run-system)
        res (GET config "_site/healthcheck")]
    (is (= 200 (:status res)))))

(deftest no-404-not-found-test
  (let [{:keys [config]} (run-system)
        res (GET config "_site/does-not-exist")]
    (is (= 500 (:status res)))
    (is (str/includes? (:body res) "Internal Server Error"))))

(deftest hello-world-resource-test
  (let [{:keys [config, system]} (run-system)
        xt-node (:juxt.site.db/xt-node system)
        _ (is xt-node)
        _ (->> [[:put :site
                 {:xt/id (get-uri config "hello-world")
                  :juxt.http/content-type "text/plain"
                  :juxt.http/content "Hello, world"}]]
               (xt/submit-tx xt-node))
        res (GET config "hello-world")]
    (is (= 200 (:status res)))
    (is (= "Hello, world" (:body res)))))

(deftest entity-test
  (let [{:keys [system]} (run-system)
        xt-node (:juxt.site.db/xt-node system)]
    (is (nil? (xt/entity (xt/db xt-node) 42)))
    (xt/submit-tx xt-node [[:put :site {:xt/id 42}]])
    (is (= {:xt/id 42} (xt/entity (xt/db xt-node) 42)))))

(deftest operation-test
  (let [{:keys [config, system]} (run-system)
        xt-node (:juxt.site.db/xt-node system)
        op-uri (get-uri config "op")
        op-doc {:xt/id op-uri
                :juxt.site/transact {:juxt.site.sci/program "[[:put :foo {:xt/id \"bar\"}]]"}}]
    (xt/submit-tx xt-node [[:put :site op-doc]])
    (operations/perform-ops!
      {:juxt.site/xt-node xt-node}
      [{:juxt.site/resource-uri op-uri
        :juxt.site/resource op-doc
        :juxt.site/operation-uri op-uri
        :juxt.site/operation op-doc}])
    (is (= [{:id "bar"}] (xt2/q xt-node '{:find [id] :where [($ :foo {:xt/id id})]})))))

(defn uri-map [config]
  {"https://auth.example.org" (get-uri config)
   "https://data.example.org" (get-uri config)})

(deftest bootstrap-test
  (let [{:keys [config, system]} (run-system)
        xt-node (:juxt.site.db/xt-node system)]
    (binding [juxt.site.test-helpers.xt/*xt-node* xt-node]

      (install-bundles!
        ["juxt/site/bootstrap"]
        (uri-map config))

      (testing "404 handler should work"
        (let [res (GET config "does-not-exist")]
          (is (= 404 (:status res))))))))

(deftest oauth-scope-test
  (let [{:keys [config, system]} (run-system)
        xt-node (:juxt.site.db/xt-node system)]
    (binding [juxt.site.test-helpers.xt/*xt-node* xt-node]

      (install-bundles!
        ["juxt/site/bootstrap"
         "juxt/site/oauth-scope"]
        (uri-map config)))))

(deftest example-users-test
  (let [{:keys [config, system]} (run-system)
        xt-node (:juxt.site.db/xt-node system)]
    (binding [juxt.site.test-helpers.xt/*xt-node* xt-node]
      (install-bundles!
        ["juxt/site/bootstrap"
         "juxt/site/oauth-scope"
         "juxt/site/user-model"
         "juxt/site/protection-spaces"
         "juxt/site/oauth-token-endpoint"
         "juxt/site/password-based-user-identity"
         "juxt/site/resources-api"
         "juxt/site/testing/basic-auth-protected-resource"
         "juxt/site/test-clients"
         "juxt/site/example-users"]
        (uri-map config))

      (let [qry '{:find [id]
                  :where [($ :site [{:xt/id id, :juxt.site/type [t ...]}])
                          [(= "https://meta.juxt.site/types/user" t)]]}]
        (is (seq (xt/q (xt/db xt-node) qry))))

      (let [qry '{:find [id]
                  ;; we insert sets like this
                  ;; #{"https://meta.juxt.site/types/user-identity", "https://meta.juxt.site/types/basic-user-identity"}
                  :where [($ :site [{:xt/id id, :juxt.site/type [t ...]}])
                          ;; if still a set (coercing to vector now in munge)
                          ;; Error: java.lang.NullPointerException: Cannot invoke "xtdb.vector.IVectorReader.rowCopier(xtdb.vector.IVectorWriter)" because "el_rdr" is null
                          [(= "https://meta.juxt.site/types/user-identity" t)]]}]
        (is (seq (xt/q (xt/db xt-node) qry)))))
    ))

(defn non-kw-map? [x]
  (and (map? x)
       (not (empty? x))
       (or (not-any? keyword? (keys x))
           (some non-kw-map? (tree-seq seqable? seq (vals x))))))

(extend-protocol xtdb.types/FromArrowType
  org.apache.arrow.vector.types.pojo.ArrowType$Map
  (<-arrow-type [_] :map))

(defn- ->put-writer [op-writer]
  (let [put-writer (.legWriter op-writer :put (org.apache.arrow.vector.types.pojo.FieldType/notNullable #xt.arrow/type :struct))
        doc-writer (.structKeyWriter put-writer "document" (org.apache.arrow.vector.types.pojo.FieldType/notNullable #xt.arrow/type :union))
        valid-from-writer (.structKeyWriter put-writer "xt$valid_from" xtdb.types/nullable-temporal-field-type)
        valid-to-writer (.structKeyWriter put-writer "xt$valid_to" xtdb.types/nullable-temporal-field-type)
        table-doc-writers (java.util.HashMap.)]
    (fn write-put! [op]
      (.startStruct put-writer)
      (let [table-doc-writer (.computeIfAbsent table-doc-writers (xtdb.util/kw->normal-form-kw (.tableName op))
                                               (xtdb.util/->jfn
                                                 (fn [table]
                                                   (.legWriter doc-writer table (org.apache.arrow.vector.types.pojo.FieldType/notNullable #xt.arrow/type :struct)))))]
        (try
          (xtdb.vector.writer/write-value!
            (->> (.doc op)
                 (into {} (map (juxt (comp xtdb.util/kw->normal-form-kw key)
                                     val))))
            table-doc-writer)
          (catch Throwable e
            (log/error e "WOT")
            (throw e)
            )))

      (xtdb.vector.writer/write-value! (.validFrom op) valid-from-writer)
      (xtdb.vector.writer/write-value! (.validTo op) valid-to-writer)

      (.endStruct put-writer))))

(defn- ->call-indexer [allocator, ra-src, wm-src, scan-emitter
                       tx-ops-rdr, {:keys [tx-key] :as tx-opts}, sci-opts]
  (let [call-leg (.legReader tx-ops-rdr :call)
        fn-id-rdr (.structKeyReader call-leg "fn-id")
        args-rdr (.structKeyReader call-leg "args")

        ;; TODO confirm/expand API that we expose to tx-fns
        sci-ctx (sci.core/init (merge-with
                                 (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                                 sci-opts
                                 {:bindings {'q (#'xtdb.indexer/tx-fn-q allocator ra-src wm-src scan-emitter tx-opts)
                                             'sql-q (partial #'xtdb.indexer/tx-fn-sql allocator ra-src wm-src tx-opts)
                                             'sleep (fn [^long n] (Thread/sleep n))
                                             '*current-tx* tx-key}}))]

    (reify xtdb.indexer.OpIndexer
      (indexOp [_ tx-op-idx]
        (try
          (let [fn-id (.getObject fn-id-rdr tx-op-idx)
                tx-fn (#'xtdb.indexer/find-fn allocator ra-src wm-src (sci.core/fork sci-ctx) tx-opts fn-id)
                args (.form (.getObject args-rdr tx-op-idx))

                res (try
                      (let [res (sci.core/binding
                                  [sci.core/out *out*
                                   sci.core/in *in*]
                                  (apply tx-fn args))]
                        (cond-> res
                                (seqable? res) doall))
                      (catch InterruptedException ie (throw ie))
                      (catch Throwable t
                        (log/warn t "unhandled error evaluating tx fn")
                        (throw (xtdb.error/runtime-err :xtdb.call/error-evaluating-tx-fn
                                                       {:fn-id fn-id, :args args}
                                                       t))))]
            (when (false? res)
              (throw @#'xtdb.indexer/abort-exn))

            ;; if the user returns `nil` or `true`, we just continue with the rest of the transaction
            (when-not (or (nil? res) (true? res))
              (xtdb.util/with-close-on-catch [tx-ops-vec (xtdb.tx-producer/open-tx-ops-vec allocator)]
                (xtdb.tx-producer/write-tx-ops! allocator (xtdb.vector.writer/->writer tx-ops-vec) (mapv xtdb.tx-producer/parse-tx-op res))
                (.setValueCount tx-ops-vec (count res))
                tx-ops-vec)))

          (catch Throwable t
            (reset! @#'xtdb.indexer/!last-tx-fn-error t)
            (throw t)))))))

(alter-var-root #'xtdb.tx-producer/->put-writer (constantly ->put-writer))
(alter-var-root #'xtdb.indexer/->call-indexer (constantly ->call-indexer))
