(ns livereload.core
  (:require
    [clojure.string :as string]
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [livereload.watch :refer [get-files-from-event check-dir-for-changes!]]
    [livereload.worker :refer [register-service-worker send-to-serviceworker]]))

(defonce state (r/atom {}))

; TODO: support drag and drop of folder
; TODO: front page design

; TODO: warn about absolute URLs in the page if present

; TODO: fix initial service worker registration (completely remove it and retry)
; TODO: test out a long page
; TODO: test nested dirs.
; TODO: test performance with many files.
; TODO: test performance with large files (e.g. images)
; TODO: test editing multiple projects at once

(defn refresh-iframe []
  (let [sub (.querySelector js/document "iframe")]
    (when sub
      (j/assoc! sub :src (j/get sub "src")))))

(defn find-references-and-reload [fname _file]
  (let [sub (.querySelector js/document "iframe")
        fname (-> fname (.split "/") (.slice 1) (.join "/"))] ; stripe project prefix
    (js/console.log "Finding JS/CSS references to" fname "and reloading.")
    (when sub
      (let [scripts (j/call-in sub [:contentDocument :querySelectorAll] (str "script[src^='" fname "']"))
            metalinks (j/call-in sub [:contentDocument :querySelectorAll] (str "link[href^='" fname "']"))
            base-url (j/call-in js/document [:location :href :split] "?")
            url (js/URL. fname base-url)
            src (-> url
                    (j/assoc! :hash (js/Math.random))
                    (.toString url)
                    (.replace base-url ""))]
        (doseq [script (.from js/Array scripts)]
          ;(j/call-in url [:searchParams :set] "_lrh" (js/Math.random))
          ;(js/console.log "URL:" (.toString url))
          ; script tags have to be created fresh
          ; they won't reload even if you add a hash
          (let [parent (j/get script :parentElement)
                clone (.createElement js/document "script")]
            (doseq [a (.from js/Array (j/get script :attributes))]
              ;(js/console.log "attribute" fname (j/get a :name))
              (when (not= (j/get a :name) "src")
                (.setAttribute clone (j/get a :name) (j/get a :value))))
            ;(.setAttribute clone "data-reload" (inc (.getAttribute src "data-reload")))
            ;(.setAttribute clone "src" (str fname "#" (js/Math.random)))
            (.setAttribute clone "src" src)
            (.removeChild parent script)
            (.appendChild parent clone)))
        (doseq [metalink (.from js/Array metalinks)]
          (.setAttribute metalink "href" src))))))

(defn handle-worker-message [event]
  ;(js/console.log "handle-worker-event" (j/get event :data))
  (let [t (j/get-in event [:data :type])]
    (when (= t "cached")
      ;(js/console.log "cached" (j/get-in event [:data :files]))
      (swap! state assoc :started true)
      (let [files (j/get-in event [:data :files])
            sub (.querySelector js/document "iframe")]
        (when sub
          (doseq [fname (.keys js/Object files)]
            (let [file (j/get files fname)]
              ;(js/console.log "checking" fname file)
              (cond
                (= fname "index.html")
                (refresh-iframe)
                (not (.startsWith (j/get file :name) "."))
                (find-references-and-reload fname file)))))))))

(defn picked-files! [*state source dir-name references]
  (send-to-serviceworker *state {:type "flush" :prefix dir-name})
  (assoc *state :file-handles (merge {:source source
                                      :dir-name dir-name}
                                     references)))

(defn handle-modified-files [state modified-files]
  (let [modified-files (->> modified-files
                            (remove (fn [[k]] (and k (string/starts-with? k "."))))
                            (into {}))]
    (when (seq modified-files)
      (js/console.log "modified-files" (clj->js modified-files))
      ; TODO: send createObjectURL of the file instead of the contents
      (let [modified-files (->> modified-files
                                (map (fn [[k v]] [k (dissoc v :content)]))
                                (into {}))]
        (send-to-serviceworker @state {:type "cache" :files modified-files})))
    (swap! state update-in [:files] #(merge %1 modified-files))))

; *** ui components *** ;

(defn component-start [state]
  (if (j/get js/window :showDirectoryPicker)
    [:button {:on-click
              (fn [_ev]
                (p/let [dir-handle (j/call js/window :showDirectoryPicker #js {:mode "read"})
                        dir-name (j/get dir-handle :name)]
                  (swap! state picked-files! :picker dir-name {:dir-handle dir-handle})))}
     "Browse"]
    [:input {:type "file"
             :webkitdirectory "true"
             :multiple true
             :on-change (fn [ev]
                          (let [files (get-files-from-event ev)
                                dir-name (-> files first (j/get :webkitRelativePath) (.split "/") first)]
                            (swap! state picked-files! :input dir-name {:files files})))}]))

(defn component-frame [state]
  [:<>
   [:div.float-ui
    [:p "livereload.net - " (-> @state :file-handles :dir-name)
     " "
     [:span.refresh-notification {:data-update (:refresh-counter @state)} "Refreshed"]]
    [:button {:on-click refresh-iframe} "Force refresh"]]
   [:iframe.main {:src (str "files/" (-> @state :file-handles :dir-name) "/index.html")}]])

(defn component-main [state]
  (if (:started @state)
    [component-frame state]
    [:div
     [:h1 "livereload.net"]
     [:p "Live-reloading web development. 100% in the browser. No build system required."]
     [:p "Choose your web app folder to get started:"]
     [:p [component-start state]]
     [:h2 "How it works"]
     [:ul
      [:li [:a {:href "#"} "Download the template"] " and unzip it on your computer."]
      [:li "Drag the folder onto this window, or choose the folder: " [component-start state]]
      [:li "Open the files in your text editor."]]
     [:p "When you make changes to the files and save them, your page will be automatically reloaded.
         Reloading is intelligent. If you modify a CSS file, only the CSS will be reloaded.
         If you modify a JS file, only the JS will be reloaded."]
     [:p "You can use this as a simple alternative to sites like codepen and Glitch.
         All your code stays private on your computer.
         Nothing is actually uploaded and there is no server to upload to, everything runs right in your browser.
         The page you're developing will automatically refresh every time you save the code."]
     [:p "Get started: " [component-start state]]]))

; *** launch *** ;

(defn start {:dev/after-load true} []
  (rdom/render [component-main state]
               (js/document.getElementById "app")))

(defn init []
  (register-service-worker state handle-worker-message)
  (check-dir-for-changes!
    #(:file-handles @state)
    #(:files @state)
    #(handle-modified-files state %))
  (start))
