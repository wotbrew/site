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
