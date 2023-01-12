(ns fluree.http-api.components.fluree
  (:require [donut.system :as ds]
            [fluree.db.json-ld.api :as db]
            [fluree.db.conn.proto :as conn-proto]
            [fluree.db.util.log :as log]))

(def conn
  #::ds{:start  (fn [{{:keys [options]} ::ds/config}]
                  (log/debug "Connecting to fluree with options:" options)
                  @(db/connect options))
        :stop   (fn [{::ds/keys [instance]}]
                  ;; TODO: Add a close-connection fn to f.d.json-ld.api
                  (when instance (conn-proto/-close instance)))
        :config {:options (ds/ref [:env :fluree/connection])}})
