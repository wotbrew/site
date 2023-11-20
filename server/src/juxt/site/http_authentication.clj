;; Copyright © 2021, JUXT LTD.

(ns juxt.site.http-authentication
  (:require
   [juxt.reap.alpha.encoders :refer [www-authenticate]]
   [clojure.tools.logging :as log]
   crypto.password.bcrypt
   [juxt.reap.alpha.decoders :as reap]
   juxt.reap.alpha.rfc7235
   [juxt.site.xtdb-polyfill :as xt]
   [clojure.string :as str]))

(defn lookup-access-token [db token]
  (first (xt/q
           db
           '{:find [(pull sub [*]) (pull at [*])]
             :keys [subject access-token]
             :where [[at :juxt.site/token tok]
                     [at :juxt.site/type "https://meta.juxt.site/types/access-token"]
                     [at :juxt.site/subject sub]
                     [sub :juxt.site/type "https://meta.juxt.site/types/subject"]]
             :in [tok]} token)))

(defn authenticate-with-bearer-auth [req db token68 protection-spaces]
  (log/tracef "Protection-spaces are %s" (pr-str protection-spaces))
  (or
   (when (seq protection-spaces)
     (let [{:keys [subject access-token]}
           (lookup-access-token db token68)
           scope (:juxt.site/scope access-token)]
       (cond-> req
         subject (assoc :juxt.site/subject-uri (:xt/id subject)
                        :juxt.site/subject subject
                        :juxt.site/access-token access-token)
         scope (assoc :juxt.site/scope scope))))
   req))

;; TODO (idea): Tie bearer token to other security aspects such as
;; remote IP so that the bearer token is more difficult to use if
;; intercepted. This can be extended to other claims in the JWT,
;; restricting the token to time periods.

;; One downside of Basic Authentication is the lack of a 'session'

(defn assoc-basic-auth-subject [req seed protection-space]
  (let [subject (into seed
                      {:xt/id (format "https://example.org/_site/subjects/%s" (random-uuid))
                       :juxt.site/type "https://meta.juxt.site/types/subject"
                       :juxt.site/protection-space (:xt/id protection-space)})]
    (assoc req
           :juxt.site/subject subject
           ;; This means that the subject's life-cycle is that of the request
           :juxt.site/subject-is-ephemeral? true)))

(defn authenticate-with-basic-auth [req db token68 protection-spaces]
  (when-let [{canonical-root-uri :juxt.site/canonical-root-uri,
              authorization-server :juxt.site/authorization-server,
              :as protection-space} (first protection-spaces)]
    (let [[_ username password]
          (re-matches
           #"([^:]*):([^:]*)"
           (String. (.decode (java.util.Base64/getDecoder) token68)))

          user-id-candidates-query
          '{:find [r]
            :in [username canonical-root-uri]
            :where [($ :site [{:xt/* r
                               :juxt.site/type [t ...]
                               :juxt.site/username username
                               :juxt.site/canonical-root-uri canonical-root-uri}])
                    [(= "https://meta.juxt.site/types/user-identity" t)]]}

          user-id-candidates
          (->> (map :r (xt/q db user-id-candidates-query username canonical-root-uri))
               (filter (fn [{:juxt.site/keys [password-hash]}] (crypto.password.bcrypt/check password password-hash))))

          app-candidates-query
          '{:find [r]
            :in [username password authorization-server]
            :where [($ :site [{:xt/* r
                               :juxt.site/type [t ...]
                               :juxt.site/client-id username
                               :juxt.site/client-secret password
                               :juxt.site/authorization-server authorization-server}])
                    [(= "https://meta.juxt.site/types/application" t)]]}

          app-candidates (map :r (xt/q db app-candidates-query username password authorization-server))

          candidates (vec (concat user-id-candidates app-candidates))]

      ;; It's unlikely, but if there are multiple user-identities or
      ;; clients with the same username/password then we will just
      ;; take the first one, but warn of this case.
      (when (> (count candidates) 1)
        (log/warnf "Multiple candidates in basic auth found for username %s, using first found" username))

      (when-let [candidate (first candidates)]
        (let [candidate-types (:juxt.site/type candidate)
              candidate-types (if (string? candidate-types) #{candidate-types} (set candidate-types))]
          (assoc-basic-auth-subject
           req
           (cond-> {}
             (contains? candidate-types "https://meta.juxt.site/types/user-identity")
             (assoc :juxt.site/user-identity (:xt/id candidate)
                    :juxt.site/user (:juxt.site/user candidate))
             (contains? candidate-types "https://meta.juxt.site/types/application")
             (assoc :juxt.site/application (:xt/id candidate)))
           protection-space))))))

(defn www-authenticate-header
  "Create the WWW-Authenticate header value"
  [db protection-spaces]
  (log/tracef "protection-spaces: %s" protection-spaces)
  (str/trim
   (www-authenticate
    (for [ps-id protection-spaces
          :let [ps (xt/entity db ps-id)
                realm (:juxt.site/realm ps)]]
      {:juxt.reap.alpha.rfc7235/auth-scheme (:juxt.site/auth-scheme ps)
       :juxt.reap.alpha.rfc7235/auth-params
       (cond-> []
         realm (conj
                {:juxt.reap.alpha.rfc7235/auth-param-name "realm"
                 :juxt.reap.alpha.rfc7235/auth-param-value realm}))}))))

(defn authenticate-with-authorization-header
  [{db :juxt.site/db, :as req}
   authorization-header protection-spaces]
  (let [{:keys [juxt.reap.alpha.rfc7235/auth-scheme
                juxt.reap.alpha.rfc7235/token68]}
        (reap/authorization authorization-header)]
    (case (.toLowerCase auth-scheme)
      "basic"
      (or
       (authenticate-with-basic-auth
        req db token68
        (filter #(= (:juxt.site/auth-scheme %) "Basic") protection-spaces))
       req)

      "bearer"
      (authenticate-with-bearer-auth
       req db token68
       (filter #(= (:juxt.site/auth-scheme %) "Bearer") protection-spaces))

      (throw
       (ex-info
        "Auth scheme unsupported"
        {:juxt.site/request-context
         (cond-> (assoc req :ring.response/status 401)
           protection-spaces
           (assoc
            :ring.response/headers
            {"www-authenticate"
             (www-authenticate-header db protection-spaces)}))})))))

(defn authenticate
  "Authenticate a request. Return a modified request, with information about user,
  roles and other credentials."
  [{db :juxt.site/db, resource :juxt.site/resource, :as req}]

  ;; TODO: This might be where we also add the 'on-behalf-of' info

  (let [protection-spaces (keep #(xt/entity db %) (:juxt.site/protection-space-uris resource []))
        ;;req (cond-> req protection-spaces (assoc :juxt.site/protection-spaces protection-spaces))
        authorization-header (get-in req [:ring.request/headers "authorization"])]

    (cond-> req
      authorization-header (authenticate-with-authorization-header authorization-header protection-spaces))))

(defn ^:deprecated login-template-model [req]
  {:query (str (:ring.request/query req))})

(defn ^:deprecated unauthorized-template-model [req]
  {:redirect (str
              (:ring.request/path req)
              (when-let [query (:ring.request/query req)] (str "?" query)))})
