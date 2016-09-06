(ns onyx-dashboard.http.server
  (:require [clojure.core.async :refer [chan timeout thread <!! alts!!]]
            [onyx-dashboard.dev :refer [is-dev? inject-devmode-html #_browser-repl start-figwheel]]
            [org.httpkit.server :as http-kit-server]
            [com.stuartsierra.component :as component]
            [clojure.java.io :as io]
            [compojure.route :refer [resources]]
            [compojure.handler :refer [api]]
            [net.cgrand.enlive-html :refer [deftemplate]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.defaults]
            [ring.util.response :refer [resource-response response content-type]]
            [compojure.core :as comp :refer (defroutes GET POST)]
            [compojure.route :as route]
            [com.stuartsierra.component :as component]
            [onyx-dashboard.tenancy :as tenancy]
            [onyx.log.curator :as zk]
            [taoensso.timbre :as timbre :refer [info error spy]]))

(deftemplate page
  (io/resource "index.html") [] [:body] (if is-dev? inject-devmode-html identity))

(def ring-defaults-config
  (assoc-in ring.middleware.defaults/site-defaults
            [:security :anti-forgery]
            {:read-token (fn [req] (-> req :params :csrf-token))}))

(defn event->uid [event]
  (get-in event [:ring-req :cookies "ring-session" :value]))

(defn start-event-handler [sente peer-config deployments tracking]
  (future
    (loop []
      (when-let [event (<!! (:ch-chsk sente))]
        ;; TODO: more sophisticated tracking,
        ;; should track by cluster id rather than user
        ;; and count the number of users tracking it. When the user count drops to 0
        ;; then stop the future.
        (let [user-id (event->uid event)] 
          (case (:id event) 
            :deployment/track (tenancy/start-tracking! (:chsk-send! sente)
                                                       peer-config
                                                       tracking
                                                       (:?data event)
                                                       user-id)
            :deployment/get-listing ((:chsk-send! sente) user-id [:deployment/listing @deployments])
            :job/kill (tenancy/kill-job peer-config 
                                        (:deployment-id (:?data event))
                                        (:job (:?data event)))
            :job/start (tenancy/start-job peer-config 
                                          (:deployment-id (:?data event))
                                          (:job (:?data event)))
            :job/restart (tenancy/restart-job peer-config 
                                              (:deployment-id (:?data event))
                                              (:job (:?data event)))
            :chsk/uidport-close (swap! tracking tenancy/stop-tracking! user-id)
            :chsk/ws-ping nil
            nil #_(println "Dunno what to do with: " event)))
        (recur)))))

(defn send-mult-fn [send-fn! connected-uids msg]
  (doseq [uid (:any @connected-uids)]
    (send-fn! uid msg)))

(defrecord HttpServer [peer-config]
  component/Lifecycle
  (start [{:keys [sente] :as component}]
    (println "Starting HTTP Server")
    (let [send-f (partial send-mult-fn 
                          (:chsk-send! sente) 
                          (:connected-uids sente))]
      (defroutes routes
        (GET  "/" [] (page))
        (GET  "/chsk" req ((:ring-ajax-get-or-ws-handshake sente) req))
        (POST "/chsk" req ((:ring-ajax-post sente) req))
        (resources "/")
        (resources "/react" {:root "react"})
        (route/not-found "Page not found"))

      (let [deployments (atom {})
            tracking (atom {})
            event-handler-fut (start-event-handler sente peer-config deployments tracking)
            handler (ring.middleware.defaults/wrap-defaults routes ring-defaults-config)
            port (Integer. (or (System/getenv "PORT") "3000"))
            server (http-kit-server/run-server handler {:port port})
            uri (format "http://localhost:%s/" (:local-port (meta server)))
            conn (zk/connect (:zookeeper/address peer-config))
            refresh-fut (future (tenancy/refresh-deployments-watch send-f conn deployments))]
        (println "Http-kit server is running at" uri)
        (assoc component
               :conn              conn 
               :server            server 
               :refresh-fut       refresh-fut
               :event-handler-fut event-handler-fut 
               :deployments       deployments 
               :tracking          tracking))))
  (stop [{:keys [server tracking deployments conn] :as component}]
    (println "Stopping HTTP Server")
    (try
      (server :timeout 100)
      (finally 
        (try 
          (swap! tracking tenancy/stop-all-tracking!)
          (finally
            (try 
              (future-cancel (:event-handler-fut component))
              (future-cancel (:refresh-fut component))
              (finally
                (if (.. conn isStarted) (zk/close conn)))))))) 
    (assoc component 
           :conn nil 
           :server nil 
           :event-handler-fut nil 
           :deployments nil 
           :tracking nil)))

(defn new-http-server [peer-config]
  (map->HttpServer {:peer-config peer-config}))
