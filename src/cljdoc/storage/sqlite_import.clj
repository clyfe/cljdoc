(ns cljdoc.storage.sqlite-import
  "This namespace contains a helper to migrate data from the Grimoire store
  to SQLite. It uses various private functions in the process and is generally
  intended to be deleted as soon as the migration has been completed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cljdoc.config :as cfg]
            [clojure.java.jdbc :as sql]
            [cljdoc.server.system :as sys]
            [cljdoc.storage.grimoire-impl :as grim]
            [cljdoc.storage.sqlite-impl :as sqlite]
            [cljdoc.storage.api :as storage]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [taoensso.tufte :as tufte :refer [defnp p profiled profile]]))

(defn import-version [db-spec grimoire-dir {:keys [group-id artifact-id version] :as v-entity}]
  (let [sqlite (storage/->SQLiteStorage db-spec)
        grimoire (storage/->GrimoireStorage grimoire-dir)
        bundle (p :bundle-docs (storage/bundle-docs grimoire v-entity))
        {:keys [group-id artifact-id version]} (:cache-id bundle)
        v-id   (or (#'sqlite/get-version-id db-spec group-id artifact-id version)
                   (do (#'sqlite/store-artifact! db-spec group-id artifact-id [version])
                       (#'sqlite/get-version-id db-spec group-id artifact-id version)))]
    (p :version-meta
       (#'sqlite/update-version-meta! db-spec v-id (-> bundle :cache-contents :version)))
    (p :ns-and-vars
       (sql/with-db-transaction [tx db-spec]
         (doseq [ns (-> bundle :cache-contents :namespaces)]
           (#'sqlite/write-ns! tx v-id ns))
         (doseq [d (-> bundle :cache-contents :defs)]
           (#'sqlite/write-var! tx v-id d))))))

(defn -main
  []
  (let [conf (cfg/config)
        sys  (sys/system-config conf)]
    (ig/init (select-keys sys [:cljdoc/sqlite]))
    (doseq [v (grim/all-versions (grim/grimoire-store (cfg/grimoire-dir conf)))]
      (println "Importing" v)
      (time (import-version (cfg/build-log-db conf) (cfg/grimoire-dir conf) v)))))

(comment
  (def conf (cfg/config))

  (import-version (cfg/build-log-db conf)
                  (cfg/grimoire-dir conf)
                  (first (grim/all-versions (grim/grimoire-store grimoire-dir))))

  )
