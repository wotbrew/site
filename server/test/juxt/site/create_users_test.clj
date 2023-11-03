;; Copyright © 2023, JUXT LTD.

(ns juxt.site.site-cli.create-users-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [juxt.site.test-helpers.xt :refer [system-xt-fixture *xt-node*]]
   [juxt.site.test-helpers.client :refer [client-secret register-user assign-user-role users events request-token]]
   [juxt.site.test-helpers.init :refer [init-fixture]]
   [juxt.site.test-helpers.fixture :refer [with-fixtures]]
   [juxt.site.test-helpers.oauth :refer [with-bearer-token] :as oauth]
   [juxt.site.test-helpers.handler :refer [handler-fixture *handler*]]
   [juxt.site.xtdb-polyfill :as xt]))

(use-fixtures :each system-xt-fixture handler-fixture init-fixture)

(deftest create-users-test
  (let [db (xt/db *xt-node*)
        client-secret (client-secret db)
        cc-token (request-token
                  {"client-secret" client-secret})

        _ (with-bearer-token cc-token
            (register-user
             {"username" "alice"
              "password" "foobar"
              "fullname" "Alice"})
            (assign-user-role
             {"username" "alice"
              "role" "SiteAdmin"}))

        alice-token (request-token
                     {"username" "alice"
                      "password" "foobar"})

        _ (with-bearer-token alice-token
            (register-user
             {"username" "bob"
              "password" "foobar"
              "fullname" "Bob"}))

        users (with-bearer-token alice-token (users))

        _ (is (= [{"juxt.site/username" "alice"
                   "fullname" "Alice"
                   "xt/id" "https://data.example.test/_site/users/alice"}
                  {"juxt.site/username" "bob"
                   "fullname" "Bob"
                   "xt/id" "https://data.example.test/_site/users/bob"}]
                 users))

        events (with-bearer-token alice-token
                 (->> (events)
                      (sort-by
                       (juxt :xtdb.api/tx-id :juxt.site/tx-event-index))))

        last-event (last events)

        db (xt/db *xt-node*)

        _ (is (= "https://data.example.test/_site/users/alice"
                 (:juxt.site/user (xt/entity db (:juxt.site/subject-uri last-event)))))]))

;; TODO: This test probably doesn't belong here, but in a dedicated
;; test for the allow response header.
(deftest allow-header-test
  (let [db (xt/db *xt-node*)
        client-secret (client-secret db)]
    (let [response
          (let [response
                (*handler*
                 {:juxt.site/uri "https://data.example.test/_site/users"
                  :ring.request/method :options})]
            (select-keys response [:ring.response/status
                                   :ring.response/headers]))]
      (is (= (get-in response [:ring.response/headers "allow"]) "GET, HEAD, OPTIONS")))

    (with-bearer-token
      (request-token
       {"client-secret" client-secret})
      (let [response
            (let [response
                  (*handler*
                   {:juxt.site/uri "https://data.example.test/_site/users"
                    :ring.request/method :options})]
              (select-keys response [:ring.response/status
                                     :ring.response/headers]))]
        (is (= (get-in response [:ring.response/headers "allow"]) "GET, HEAD, POST, OPTIONS"))))))
