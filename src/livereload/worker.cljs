(ns livereload.worker
  (:require
    [applied-science.js-interop :as j]
    [promesa.core :as p]))

(def cache-name "livereload-v1")

(defn cache-files [files prefix]
  (js/console.log "cache-files" prefix files)
  (p/let [cache (.open js/caches cache-name)]
    (js/console.log "cache" cache)
    (p/all
      (map (fn [filename]
             (let [url (str prefix filename)
                   file (j/get files filename)
                   response (js/Response. (j/get file :content) #js {:headers #js {"Content-Type" (j/get file :type)}})]
               (js/console.log "caching" url)
               (.put cache url response)))
           (js/Object.keys files)))
    (js/console.log "Caching completed.")))

(defn flush-cache [prefix]
  (p/let [cache (.open js/caches cache-name)
          cache-keys (.keys cache)]
    (p/all (map (fn [request]
                  (when (.startsWith (j/get request :url) prefix)
                    (.delete cache request)))
                cache-keys))
    (js/console.log "Cache flushed.")))

(defn handle-message [event]
  (js/console.log "handle-message" event)
  (let [data (j/get event :data)
        msg-type (j/get data :type)]
    (cond (= msg-type "cache")
          (let [files (j/get data :files)
                prefix (j/get data :prefix)]
            (cache-files files prefix))
          (= msg-type "flush")
          (let [prefix (.-prefix data)]
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
