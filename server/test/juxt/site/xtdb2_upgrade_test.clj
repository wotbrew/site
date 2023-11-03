;; Copyright © 2022, JUXT LTD.

(ns juxt.site.xtdb2-upgrade-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [juxt.site.main :as main]
            [java-http-clj.core :as http]
            [juxt.site.xtdb-polyfill :as xt]))

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

(deftest healthcheck-test
  (let [{:keys [config]} (run-system)
        {listener-opts [:juxt.site.listener/listener :juxt.site.listener/secondary-listener]} config
        {listener-port :juxt.site/port} listener-opts
        res (http/send {:uri (format "http://localhost:%s/_site/healthcheck" listener-port), :method :get})]
    (is (= 200 (:status res)))))
