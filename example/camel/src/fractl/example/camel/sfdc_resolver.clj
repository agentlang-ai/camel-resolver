(ns fractl.example.camel.sfdc-resolver
  (:require [clojure.string :as s]
            [selmer.parser :as st]
            [fractl.util :as u]
            [fractl.store.util :as stu]
            [fractl.component :as cn]
            [fractl.lang.internal :as li]
            [fractl.datafmt.json :as json]
            [fractl.resolver.core :as r]
            [fractl.resolver.registry :as rg]
            [fractl.evaluator :as ev]
            [fractl.resolver.camel.core :as camel])
  (:import [org.apache.camel Component]
           [org.apache.camel.component.salesforce SalesforceComponent AuthenticationType]))

(defn ^Component make-component [config]
  (let [^SalesforceComponent sfc (SalesforceComponent.)
        config (or (:salesforce config) config)
        inst-url (or (:instance-url config)
                     (u/getenv "SFDC_INSTANCE_URL"))]
    (.setClientId sfc (or (:client-id config)
                          (u/getenv "SFDC_CLIENT_ID")))
    (.setClientSecret sfc (or (:client-secret config)
                              (u/getenv "SFDC_CLIENT_SECRET")))
    (.setAuthenticationType sfc (AuthenticationType/valueOf "CLIENT_CREDENTIALS"))
    (.setInstanceUrl sfc inst-url)
    (.setLoginUrl sfc inst-url)
    sfc))

(def ^:private endpoint-templates
  {:create "salesforce:createSObject?apiVersion=59.0&rawPayload=true&format=JSON&sObjectName={{sObjectName}}"
   :query "salesforce:query?apiVersion=59.0&rawPayload=true&format=JSON&sObjectName={{sObjectName}}"})

(defn- sf-create [camel-component instance]
  (let [[_ n] (li/split-path (cn/instance-type instance))
        ep (st/render (:create endpoint-templates) {:sObjectName (name n)})
        result (camel/exec-route {:endpoint ep
                                  :user-arg (json/encode
                                             (dissoc
                                              (cn/instance-attributes instance)
                                              li/id-attr))
                                  :camel-component camel-component})
        r (when result (json/decode result))]
    (when (:success r) (assoc instance :Id (:id r)))))

(defn- lookup-all [[_ sobj-name :as entity-name]]
  (str "SELECT FIELDS(STANDARD) FROM " (name sobj-name)))

(defn- lookup-by-expr [[_ sobj-name :as entity-name]
                       where-clause]
  (str "SELECT FIELDS(STANDARD) FROM " (name sobj-name)
       " WHERE " where-clause))

(defn- as-raw-sql-val [v]
  (if (or (string? v) (number? v) (boolean? v))
    v
    (stu/encode-clj-object v)))

(defn- as-sql-expr [[opr attr v]]
  [(str (name attr) " " (name opr) " ?") [(as-raw-sql-val v)]])

(defn- replace-placeholders [sql params]
  (reduce (fn [s p]
            (s/replace-first
             s #"\?"
             (if (string? p)
               (str "'" p "'")
               (str p))))
          sql params))

(defn- sf-query [camel-component [entity-name {clause :where} :as param]]
  (let [soql (cond
               (or (= clause :*) (nil? (seq clause)))
               (lookup-all entity-name)

               :else
               (let [opr (first clause)
                     where-clause (case opr
                                    (:and :or)
                                    (let [sql-exp (mapv as-sql-expr (rest clause))
                                          exps (mapv first sql-exp)
                                          params (flatten (mapv second sql-exp))]
                                      (replace-placeholders
                                       (s/join (str " " (s/upper-case (name opr)) " ") exps)
                                       params))
                                    (let [[s params] (as-sql-expr clause)]
                                      (replace-placeholders s params)))]
                 (lookup-by-expr entity-name where-clause)))
        [_ n] entity-name
        ep (st/render
            (:query endpoint-templates)
            {:sObjectName (name n)})
        result (camel/exec-route {:endpoint ep
                                  :user-arg soql
                                  :camel-component camel-component})]
    (:records (json/decode result))))

(def ^:private registered-paths (atom #{}))

(defn- make-full-path [entity-name]
  (when-let [[c n] (first (filter (fn [[_ en]] (= entity-name en)) @registered-paths))]
    (li/make-path c n)))

(defn- crud-info [subs-response]
  (let [payload (get-in subs-response [:data :payload])
        evt (:ChangeEventHeader payload)
        tag (keyword (s/lower-case (:changeType evt)))
        entity-name (make-full-path (keyword (:entityName evt)))]
    (when entity-name
      [tag entity-name (cn/make-instance {entity-name payload})])))

(defn- subscribe-to-change-events [camel-component]
  (camel/exec-route
   {:endpoint "salesforce:subscribe:/data/ChangeEvents?rawPayload=true"
    :camel-component camel-component
    :callback #(let [r (json/decode %)
                     [tag entity-name inst :as cinfo] (crud-info r)]
                 (when cinfo
                   (cn/force-fire-post-event ev/eval-all-dataflows tag entity-name inst)))}
   false))

(defn- sf-on-set-path [[tag path]]
  (when (= tag :override)
    (-> path cn/disable-post-events cn/with-external-schema))
  (swap! registered-paths conj (li/split-path path))
  path)

(rg/register-resolver-type
 :camel-salesforce
 (fn [_ _]
   (let [c (make-component nil)]
     (subscribe-to-change-events c)
     (r/make-resolver
      :camel-salesforce
      {:create (partial sf-create c)
       :query (partial sf-query c)
       :on-set-path sf-on-set-path}))))
