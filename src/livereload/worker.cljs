(ns livereload.worker
  (:require
    [applied-science.js-interop :as j]
    [promesa.core :as p]))

(def cache-name "livereload-v1")

(defn cache-files [files source]
  (js/console.log "cache-files" files)
  (p/let [cache (.open js/caches cache-name)]
    (p/all
      (map (fn [filename]
             (let [file (j/get files filename)
                   response (js/Response. (j/get file :actual-file) #js {:headers #js {"Content-Type" (j/get file :type)
                                                                                       "Cache-Control" "no-store, no-cache"}})]
               (js/console.log "caching" filename)
               (.put cache filename response)))
           (js/Object.keys files)))
    (js/console.log "Caching completed.")
    (.postMessage source #js {:type "cached" :files files})))

(defn flush-cache [prefix]
  (p/let [cache (.open js/caches cache-name)
          cache-keys (.keys cache)]
    (p/all (map (fn [request]
                  (when (or (.startsWith (j/get request :url) (str prefix "/")) (nil? prefix))
                    (.delete cache request)))
                cache-keys))
    (js/console.log "Cache flushed.")))

(defn handle-message [event]
  (js/console.log "handle-message" event)
  (let [data (j/get event :data)
        msg-type (j/get data :type)
        source (j/get event :source)]
    (cond (= msg-type "cache")
          (let [files (j/get data :files)]
            (cache-files files source))
          (= msg-type "flush")
          (let [prefix (j/get data :prefix)]
            (flush-cache prefix)))))

(defn handle-fetch [event]
  (js/console.log "handle-fetch" event)
  (let [url (j/get-in event [:request :url])
        origin (j/get-in js/self [:location :origin])]
    (when (.startsWith url origin)
      (.respondWith event
                    (p/let [path (.replace url origin "")
                            cache (.open js/caches cache-name)
                            response (.match cache path)]
                      (js/console.log "response" path response)
                      response)))))

(defn init []
  (js/console.log "loaded")
  (js/self.addEventListener "message" #(handle-message %1))
  (js/self.addEventListener "fetch" #(handle-fetch %1)))

; *** client stuff *** ;

(defn register-service-worker [state message-handler]
  (when (j/get js/navigator :serviceWorker)
    (p/catch
      (p/let [sw (j/call-in js/navigator [:serviceWorker :register] "files/worker.js")]
        (swap! state assoc :sw sw)
        (js/console.log "Registration:" sw))
      (fn [err] (js/console.error err)))
    (j/call-in js/navigator [:serviceWorker :addEventListener] "message" #(message-handler %))))

(defn send-to-serviceworker [*state message]
  (.postMessage (-> *state :sw (j/get :active)) (clj->js message)))
