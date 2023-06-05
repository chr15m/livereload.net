(ns livereload.core
  (:require
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [livereload.watch :refer [get-files-from-event check-dir-for-changes!]]))

(defonce state (r/atom {}))

; TODO: prefix cache based on disk directory name
; TODO: test completely removing service worker and starting registration from scratch
; TODO: support drag and drop of folder
; TODO: test out a long page
; TODO: warn about absolute URLs in the page if present

(defn refresh-iframe []
  (let [sub (.querySelector js/document "iframe")]
    (when sub
      (j/assoc! sub :src (j/get sub "src")))))

(defn handle-worker-message [event]
  (js/console.log "handle-worker-event" (j/get event :data))
  (let [t (j/get-in event [:data :type])]
    (when (= t "cached")
      (js/console.log "cached" (j/get-in event [:data :files]))
      (swap! state assoc :started true)
      (let [files (j/get-in event [:data :files])
            sub (.querySelector js/document "iframe")]
        (when sub
          ;(js/console.log "scripts" (j/call-in sub [:contentWindow :document :querySelector] "script"))
          (doseq [fname (.keys js/Object files)]
            (let [file (j/get files fname)]
              (js/console.log "checking" fname file)
              (cond
                (= fname "index.html")
                (refresh-iframe)
                :else
                (js/console.log "Finding JS/CSS references to" fname "and reloading.")))))))))

(defn register-service-worker [state]
  (when (j/get js/navigator :serviceWorker)
    (p/catch
      (p/let [sw (j/call-in js/navigator [:serviceWorker :register] "files/worker.js")]
        (swap! state assoc :sw sw)
        (js/console.log "Registration:" sw)
        (.postMessage (-> sw (j/get :active)) #js {:type "flush"}))
      (fn [err] (js/console.error err)))
    (j/call-in js/navigator [:serviceWorker :addEventListener] "message" #(handle-worker-message %))))

(defn component-start [state]
  (if (j/get js/window :showDirectoryPicker)
    [:button {:on-click
              (fn [_ev]
                (p/let [dir-handle (j/call js/window :showDirectoryPicker #js {:mode "read"})
                        dir-name (j/get dir-handle :name)]
                  (swap! state assoc :file-handles
                         {:source :picker
                          :dir-name dir-name
                          :dir-handle dir-handle})))}
     "Browse"]
    [:input {:type "file"
             :webkitdirectory "true"
             :multiple true
             :on-change (fn [ev]
                          (let [files (get-files-from-event ev)
                                dir-name (-> files first (j/get :webkitRelativePath) (.split "/") first)]
                          (swap! state assoc :file-handles
                                 {:source :input
                                  :dir-name dir-name
                                  :files files})))}]))

(defn component-frame [state]
  [:<>
   [:div.float-ui
    [:p "livereload.net - " (-> @state :file-handles :dir-name)
     " "
     [:span.refresh-notification {:data-update (:refresh-counter @state)} "Refreshed"]]
    [:button {:on-click refresh-iframe} "Force refresh"]]
   [:iframe.main {:src "files/index.html"}]])

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

(defn handle-modified-files [state modified-files]
  (when (seq modified-files)
    (js/console.log "modified-files" (clj->js modified-files))
    ; TODO: send createObjectURL of the file instead of the contents
    (.postMessage (-> @state :sw (j/get :active))
                  (clj->js {:type "cache" :files modified-files})))
  (swap! state update-in [:files] #(merge %1 modified-files)))

(defn start {:dev/after-load true} []
  (rdom/render [component-main state]
               (js/document.getElementById "app")))

(defn init []
  (register-service-worker state)
  (check-dir-for-changes!
    #(:file-handles @state)
    #(:files @state)
    #(handle-modified-files state %))
  (start))
