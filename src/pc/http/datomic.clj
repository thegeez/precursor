(ns pc.http.datomic
  (:require [cheshire.core :as json]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [org.httpkit.client :as http]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.email :as email]
            [pc.http.datomic-common :as common]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.chat :as chat-model]
            [pc.profile :as profile]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+)])
  (:import java.util.UUID))

(defn entity-id-request [eid-count]
  (cond (not (number? eid-count))
        {:status 400 :body (pr-str {:error "count is required and should be a number"})}
        (< 100 eid-count)
        {:status 400 :body (pr-str {:error "You can only ask for 100 entity ids"})}
        :else
        {:status 200 :body (pr-str {:entity-ids (pcd/generate-eids (pcd/conn) eid-count)})}))

;; TODO: is the transaction guaranteed to be the first? Can there be multiple?
(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (d/entity (:db-after transaction) txid)))

(defn handle-precursor-pings [document-id datoms]
  (if-let [ping-datoms (seq (filter #(and (= :chat/body (:a %))
                                            (re-find #"(?i)@prcrsr|@danny|@daniel" (:v %)))
                                      datoms))]
    (doseq [datom ping-datoms
            :let [message (format "<%s|%s>: %s"
                                  (urls/doc document-id) document-id (:v datom))]]
      (http/post "https://hooks.slack.com/services/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"
                 {:form-params {"payload" (json/encode {:text message})}}))
    (when (and (first (filter #(= :chat/body (:a %)) datoms))
               (= 1 (count (get @sente/document-subs document-id))))
      (let [message (format "<%s|%s> is typing messages to himself: \n %s"
                            (urls/doc document-id) document-id (:v (first (filter #(= :chat/body (:a %)) datoms))))]
        (http/post "https://hooks.slack.com/services/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"
                   {:form-params {"payload" (json/encode {:text message})}})))))

(def outgoing-whitelist
  #{:layer/name
    :layer/uuid
    :layer/type
    :layer/start-x
    :layer/start-y
    :layer/end-x
    :layer/end-y
    :layer/rx
    :layer/ry
    :layer/fill
    :layer/stroke-width
    :layer/stroke-color
    :layer/opacity

    :entity/type

    :layer/start-sx
    :layer/start-sy

    :layer/font-family
    :layer/text
    :layer/font-size
    :layer/path
    :layer/child
    :layer/ui-id
    :layer/ui-target
    :session/uuid
    :document/id ;; TODO: for layers use layer/document
    :document/uuid
    :document/name
    :document/creator
    :document/collaborators
    :document/privacy
    :chat/body
    :chat/color
    :chat/cust-name
    :cust/uuid
    :client/timestamp
    :server/timestamp

    :permission/document
    :permission/cust ;; translated
    :permission/permits
    :permission/grant-date

    :access-grant/document
    :access-grant/email
    :access-grant/grant-date

    :access-request/document
    :access-request/cust ;; translated
    :access-request/status
    :access-request/create-date
    :access-request/deny-date

    })

(defn translate-datom-dispatch-fn [db d] (:a d))

(defmulti translate-datom translate-datom-dispatch-fn)

(defmethod translate-datom :default [db d]
  d)

;; TODO: teach the frontend how to lookup name from cust/uuid
;;       this will break if something else is associating cust/uuids
(defmethod translate-datom :cust/uuid [db d]
  (if (:chat/body (d/entity db (:e d)))
    (assoc d
           :a :chat/cust-name
           :v (or (chat-model/find-chat-name db (:v d))
                  (subs (str (:v d)) 0 6)))
    d))

(defmethod translate-datom :permission/cust [db d]
  (update-in d [:v] #(:cust/email (d/entity db %))))

(defmethod translate-datom :access-request/cust [db d]
  (update-in d [:v] #(:cust/email (d/entity db %))))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (schema/get-ident a)
        v (if (contains? (schema/enums) a)
            (schema/get-ident v)
            v)]
    (->> {:e e :a a :v v :tx tx :added added}
      (translate-datom db))))

(defn whitelisted? [datom]
  (contains? outgoing-whitelist (:a datom)))

(defn notify-subscribers [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:document/id annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter whitelisted?)
                                 seq)]
        (sente/notify-transaction (merge {:tx-data public-datoms}
                                         annotations))
        (when (profile/prod?)
          (future
            (utils/with-report-exceptions
              (handle-precursor-pings (:document/id annotations) public-datoms))))))))

(defn send-emails [transaction]
  (let [annotations (delay (get-annotations transaction))]
    (doseq [datom (:tx-data transaction)]
      (when (and (= :needs-email (schema/get-ident (:a datom)))
                 (not (contains? #{:transaction.source/unmark-sent-email
                                   :transaction.source/mark-sent-email}
                                 (:transaction/source @annotations))))
        (log/infof "Queueing email for %s" (:e datom))
        (future
          (utils/with-report-exceptions
            (email/send-entity-email (:db-after transaction) (schema/get-ident (:v datom)) (:e datom))))))))

(defn handle-transaction [transaction]
  (def myt transaction)
  (notify-subscribers transaction)
  (send-emails transaction))

(defn init []
  (let [conn (pcd/conn)
        tap (async/chan (async/sliding-buffer 1024))]
    (async/tap pcd/tx-report-mult tap)
    (async/go-loop []
                   (when-let [transaction (async/<! tap)]
                     (utils/with-report-exceptions
                       (handle-transaction transaction))
                     (recur)))))
