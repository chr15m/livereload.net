(ns livereload.core
  (:require
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    [reagent.core :as r]
    [reagent.dom :as rdom]))

(defonce state (r/atom {}))

(defn unpack-entries [entries results]
  (p/let [n (j/call entries :next)
          done (j/get n :done)
          [filename handle] (j/get n :value)
          results (conj results [filename handle])]
    (if done
      results
      (unpack-entries entries results))))

(defn get-files-from-picker [dir-handle]
  (p/let [entries (j/call dir-handle :entries)
          handles (clj->js (unpack-entries entries []))
          file-handle-promises (.map (js/Array.from handles) (fn [[_filename handle]] (when handle (.getFile handle))))
          files (js/Promise.all file-handle-promises)]
    (.filter files identity)))

(defn compare-file-last-modified [files known-files]
  (p/let [changed-files (js/Promise.all
                          (.map files
                                (fn [f]
                                  (let [n (j/get f :webkitRelativePath)
                                        n (if (seq n) n (j/get f :name))]
                                    (when (not= (j/get f :lastModified)
                                                (:lastModified (get known-files n)))
                                      (p/let [content (j/call f :text)]
                                        [n (j/assoc! (j/select-keys f [:lastModified :size :type :name])
                                                     :content content)]))))))
          changed-files (js->clj changed-files :keywordize-keys true)]
    (into {} changed-files)))

(defn compare-file-contents [files known-files]
  ; on firefox we have to get content and compare it because all other fields are frozen
  (p/let [files-struct (p/all (for [f files]
                                (p/let [content (j/call f :text)]
                                  [(j/get f :webkitRelativePath)
                                   (js->clj
                                     (j/assoc! (j/select-keys f [:lastModified :size :type :name])
                                               :content content)
                                     :keywordize-keys true)])))
          files-struct (into {} files-struct)
          changes (reduce (fn [changes [filename file-struct]]
                            (if (not= (:content file-struct)
                                      (:content (get known-files filename)))
                              (assoc changes filename file-struct)
                              changes))
                          {} files-struct)]
    changes))

(defn check-dir-for-changes! [state]
  (p/catch
    (p/let [{:keys [source files dir-handle]} (:file-handles @state)
            known-files (:files @state)
            modified-files (case source
                             :picker
                             (p/let [files (get-files-from-picker dir-handle)]
                               (compare-file-last-modified (js/Array.from files) known-files))
                             :input
                             (compare-file-contents files known-files)
                             {})]
      (when (seq modified-files)
        (js/console.log "modified-files" (clj->js modified-files)))
      ; TODO: trigger file changed callback
      (swap! state update-in [:files] #(merge %1 modified-files))
      (js/setTimeout #(check-dir-for-changes! state) 250))
    (fn [err]
      (js/console.error err)
      (js/setTimeout #(check-dir-for-changes! state) 2000))))

(defn component-start [state]
  (if (j/get js/window :showDirectoryPicker)
    [:button {:on-click
              (fn [_ev]
                (p/let [dir-handle (j/call js/window :showDirectoryPicker #js {:mode "read"})]
                  (swap! state assoc :file-handles {:source :picker :dir-handle dir-handle})))}
     "Browse"]
    [:input {:type "file"
             :webkitdirectory "true"
             :multiple true
             :on-change (fn [ev]
                          (swap! state assoc :file-handles
                                 {:source :input
                                  :files (js/Array.from (j/get-in ev [:target :files]))}))}]))

(defn component-main [state]
  [:div
   [:h1 "livereload.net"]
   [:p "Live-reloading web development without a build system."]
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
   [:p "Get started: " [component-start state]]])

(defn start {:dev/after-load true} []
  (rdom/render [component-main state]
               (js/document.getElementById "app")))

(defn init []
  (check-dir-for-changes! state)
  (start))
