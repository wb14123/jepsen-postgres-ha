
(ns jepsen.postgres.check-primary
  "Test there is no two primaries at any given time."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
             [string :as str]]
            [dom-top.core :refer [with-retry]]
            [elle.core :as elle]
            [jepsen [checker :as checker]
             [client :as client]
             [db :as db]
             [generator :as gen]
             [util :as util :refer [parse-long]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.postgres-db :as pdb]
            [jepsen.tests.cycle.append :as append]
            [jepsen.postgres [client :as c]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [slingshot.slingshot :refer [try+ throw+]]))


(defrecord Client []
  client/Client
  (open! [this test node]
    this)

  (setup! [this test])

  (invoke! [_ test op]
    (let [test-db (:db test)
          primaries (db/primaries test-db test)
          debug-info (pdb/try-one-node (:nodes test) "kubectl get pods -o wide -L role")
          ]
      (if (> (count primaries) 1)
        (assoc op :type :fail, :value {:primaries primaries, :debug-info debug-info})
        (assoc op :type :ok, :value {:primaries primaries, :debug-info debug-info}))))

  (teardown! [this test])

  (close! [_ test]))

(defn checker
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [errs (->> history
                      (filter (comp #{:fail} :type)))]
        {:valid? (not (seq errs))
         :errors errs}))))


(defn gen-r [] {:type :invoke, :f :read-primary})

(defn workload
  "A package of client, checker, etc."
  [opts]
  {:client    (Client.)
   :checker   (checker)
   :generator gen-r})