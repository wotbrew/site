;; Copyright © 2021, JUXT LTD.

(ns juxt.site.main
  (:require
   juxt.site.schema
   [aero.core :as aero]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(def ^:dynamic *system* nil)

(def profile :prod)

(let [lock (Object.)]
  (defn- load-namespaces
    [system-config]
    (locking lock
      (ig/load-namespaces system-config))))

;; There will be integrant tags in our Aero configuration. We need to
;; let Aero know about them using this defmethod.
(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(def config
  "Read EDN config, with the given aero options. See Aero docs at
  https://github.com/juxt/aero for details."
  (memoize
   (fn []
     (log/infof "Configuration profile: %s" (name profile))
     (let [config-file (or (some-> (System/getProperty "site.config") io/file)
                           (io/file (System/getProperty "user.home") ".config/site/config.edn"))]
       (when-not (.exists config-file)
         (log/error (str "Configuration file does not exist: " (.getAbsolutePath config-file)))
         (throw (ex-info
                 (str "Please copy a configuration file to " (.getAbsolutePath config-file))
                 {})))
       (log/debug "Loading configuration from" (.getAbsolutePath config-file))
       (aero/read-config config-file {:profile profile})))))

(defn system-config
  "Construct a new system, configured with the given profile"
  []
  (let [config (config)
        system-config (:ig/system config)]
    (load-namespaces system-config)
    (ig/prep system-config)))

(defn -main [& _]
  (log/info "Starting system")

  (let [system-config (system-config)
        system (ig/init system-config)
        halted (atom false)
        halt-if-running
        (fn []
          (when-not @halted
            (try
              (reset! halted true)
              (ig/halt! system)
              (catch Throwable t
                (log/error t "Exception on exit")))))]
    (log/infof "Configuration: %s" (pr-str system-config))

    (log/info "System started and ready...")
    (log/trace "TRACE on")

    (Thread/setDefaultUncaughtExceptionHandler
      (reify Thread$UncaughtExceptionHandler
        (uncaughtException [_ _ throwable]
          (throw (ex-info "Default Exception caught:" throwable)))))

    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. (fn [] (halt-if-running))))

    (alter-var-root #'*system* (constantly system))

    (try
      (when-not (Thread/interrupted) @(promise))
      (catch InterruptedException _
        (log/info "System exiting")))

    (halt-if-running)))
