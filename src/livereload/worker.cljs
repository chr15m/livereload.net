(ns livereload.worker
  (:require
    [applied-science.js-interop :as j]
    [promesa.core :as p]))

(def cache-name "livereload-v1")

; TODO: remove prefix stuff. check if it's used if there are sub-folders.

(defn cache-files [files prefix source]
  (js/console.log "cache-files" prefix files)
  (p/let [cache (.open js/caches cache-name)]
    (p/all
      (map (fn [filename]
             (let [url (str (when prefix (str prefix "/")) filename)
                   file (j/get files filename)
                   response (js/Response. (j/get file :actual-file) #js {:headers #js {"Content-Type" (j/get file :type)
                                                                                       "Cache-Control" "no-store, no-cache"}})]
               (js/console.log "caching" url)
               (.put cache url response)))
           (js/Object.keys files)))
    (js/console.log "Caching completed.")
    (.postMessage source #js {:type "cached" :files files :prefix prefix})))

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
          (let [files (j/get data :files)
                prefix (j/get data :prefix)]
            (cache-files files prefix source))
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
