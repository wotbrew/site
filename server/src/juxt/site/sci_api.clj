;; Copyright © 2023, JUXT LTD.

(ns juxt.site.sci-api
  (:require [clojure.tools.logging :as log]
            [juxt.site.jwt :as jwt]
            [juxt.site.xtdb-polyfill :as xt]))

(defn lookup-applications [q client-id]
  (seq
    (map first
         (try
           (q '{:find [(pull e [*])]
                :where [[e :juxt.site/type "https://meta.juxt.site/types/application"]
                        [e :juxt.site/client-id client-id]]
                :in [client-id]} client-id)
           (catch Exception cause
             (throw
               (ex-info
                 (format "Failed to lookup client: %s" client-id)
                 {:client-id client-id} cause)))))))

(defn match-identity [q m]
  (log/infof "Matching identity: %s" m)
  (let [qry '(-> (unify (from :site {:bind [{:xt/id id, :juxt.site/type "https://meta.juxt.site/types/user-identity"}]})
                        (left-join (from :site {:bind [{:xt/id id, :juxt.site.jwt.claims/sub sub}]}) {:bind [id sub]})
                        (left-join (from :site {:bind [{:xt/id id, :juxt.site.jwt.claims/nickname nickname}]}) {:bind [id nickname]}))
                 (where (or (= ?nickname nickname) (= ?sub sub))))
        _ (log/infof "Query used: %s" (pr-str qry))
        result (:id (first (q [qry {:nickname (:juxt.site.jwt.claims/nickname m), :sub (:juxt.site.jwt.claims/sub m)}])))
        _ (log/infof "Result: %s" result)]
    result))

(defn make-access-token [q claims keypair-id]
  (let [keypair (xt/entity-for-q q keypair-id)]
    (when-not keypair
      (throw (ex-info (format "Keypair not found: %s" keypair-id) {:keypair-id keypair-id})))
    (try
      (jwt/new-access-token claims keypair)
      (catch Exception cause
        (throw
          (ex-info
            "Failed to make access token"
            {:claims claims
             :keypair-id keypair-id}
            cause))))))

(defn lookup-authorization-code [q code]
  (first
    (map first
         (q '{:find [(pull e [*])]
              :where [[e :juxt.site/code code]
                      [e :juxt.site/type "https://meta.juxt.site/types/authorization-code"]]
              :in [code]}
            code))))

(defn lookup-refresh-token [q token]
  (first
    (map first
         (q '{:find [(pull e [*])]
              :where [[e :juxt.site/token token]
                      [e :juxt.site/type "https://meta.juxt.site/types/refresh-token"]]
              :in [token]}
            token))))

(defn match-identity-with-password [q m password password-hash-key]
  (ffirst
    (q {:find ['id]
        :where (into
                 [['id :juxt.site/type "https://meta.juxt.site/types/user-identity"]
                  ['id password-hash-key 'password-hash]
                  ['(crypto.password.bcrypt/check password password-hash)]
                  ]
                 (for [[k v] m] ['id k v]))
        :in ['password]} password)))
