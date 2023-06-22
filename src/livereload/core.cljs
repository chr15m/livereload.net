(ns livereload.core
  (:require
    [clojure.string :as string]
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [livereload.watch :refer [get-files-from-event check-dir-for-changes!]]
    [livereload.worker :refer [register-service-worker send-to-serviceworker]]
    [shadow.resource :as rc]))

(defonce state (r/atom {}))

; TODO: UI is crunched on low rez windows

; TODO: copy templates across from Slingcode and add DoodleCSS

; TODO: build static html single file

; TODO: test performance with many files.
; TODO: test performance with large files (e.g. images)
; test nested dirs
; test editing multiple projects at once
; test out a long page
; fix initial service worker registration (completely remove it and retry)
; social meta tags

; TODO: test Safari select & drop
; TODO: test Edge select & drop
; TODO: test Chrome select & drop
; TODO: test Firefox select & drop

; TODO: warn about absolute URLs in the page if present

(defn refresh-notification []
  (when-let [r (.querySelector js/document ".refresh-notification")]
    (j/call-in r [:classList :remove] "fade")
    (j/get r :offsetWidth)
    (j/call-in r [:classList :add] "fade")))

(defn refresh-iframe []
  (js/console.log "refresh-iframe")
  (let [sub (.querySelector js/document "iframe.livereload-main")]
    (js/console.log "sub" sub)
    (when sub
      (j/assoc! sub :src (j/get sub "src"))
      (refresh-notification))))

(defn find-references-and-reload [fname _file]
  (let [sub (.querySelector js/document "iframe.livereload-main")
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
          (.setAttribute metalink "href" src)))
      (refresh-notification))))

(defn handle-worker-message [event]
  ;(js/console.log "handle-worker-event" (j/get event :data))
  (let [t (j/get-in event [:data :type])]
    (when (= t "cached")
      ;(js/console.log "cached" (j/get-in event [:data :files]))
      (swap! state assoc :started true)
      (let [files (j/get-in event [:data :files])
            sub (.querySelector js/document "iframe.livereload-main")]
        (when sub
          (doseq [fname (.keys js/Object files)]
            (let [file (j/get files fname)]
              ;(js/console.log "checking" fname (j/get sub :src))
              (cond
                (.endsWith (j/get sub :src) fname)
                (refresh-iframe)
                (not (.startsWith (j/get file :name) "."))
                (find-references-and-reload fname file)))))))))

(defn picked-files! [*state source dir-name references]
  (send-to-serviceworker *state {:type "flush" :prefix dir-name})
  (assoc *state :file-handles (merge {:source source
                                      :dir-name dir-name}
                                     references)))

(defn dropped-files! [*state ev]
  (js/console.log "dropped" ev)
  (.preventDefault ev)
  (let [items (j/get-in ev [:dataTransfer :items])
        first-item (first items)
        fs-entry (when first-item
                   (or (j/call first-item :webkitGetAsEntry)
                       (j/call first-item :getAsEntry)))
        dir-name (when fs-entry (j/get fs-entry :name))]
    (js/console.log "FS Entry" fs-entry)
    (if (and fs-entry (j/get fs-entry :isDirectory))
      (picked-files! *state :dropped dir-name {:fs-entry fs-entry})
      *state)))

(defn handle-modified-files! [*state modified-files]
  (let [modified-files (->> modified-files
                            (remove (fn [[k]] (and k (string/starts-with? k "."))))
                            (into {}))]
    (when (seq modified-files)
      (js/console.log "modified-files" (clj->js modified-files))
      (let [modified-files (->> modified-files
                                (map (fn [[k v]] [k (dissoc v :content)]))
                                (into {}))]
        (send-to-serviceworker *state {:type "cache" :files modified-files})))
    modified-files))

(defn activate-opener []
  (.click (.querySelector js/document ".dropzone>.activate")))

; *** ui components *** ;

(defn component-icon [c icon-svg]
  [:div.icon
   (merge c
     {:dangerouslySetInnerHTML
      {:__html icon-svg}})])

(defn component-start [state]
  [:div.dropzone
   {:on-click activate-opener}
   [component-icon {:class "x4"} (rc/inline "fa/folder-open-o.svg")]
   [:p
    "Drag web project folder here." [:br]
    "Or click to choose folder."]
   (if (j/get js/window :showDirectoryPicker)
     [:button.activate
      {:on-click
       (fn [_ev]
         (p/let [dir-handle (j/call js/window :showDirectoryPicker #js {:mode "read"})
                 dir-name (j/get dir-handle :name)]
           (swap! state picked-files! :picker dir-name {:dir-handle dir-handle})))}
      "Choose dev folder"]
     [:input.activate
      {:type "file"
       :webkitdirectory "true"
       :multiple true
       :on-change (fn [ev]
                    (let [files (get-files-from-event ev)
                          dir-name (-> files first (j/get :webkitRelativePath) (.split "/") first)]
                      (swap! state picked-files! :input dir-name {:files files})))}])])

(defn component-frame [state]
  [:iframe.livereload-main
   {:src (str "files/" (-> @state :file-handles :dir-name) "/index.html")}])

(defn component-header []
  [:header
   [:div.lines.logo
    {:dangerouslySetInnerHTML
     {:__html (rc/inline "lines.svg")}}]
   (let [dir-name (-> @state :file-handles :dir-name)]
     [:<>
      (when dir-name
        [:div.ui
         [:p.dir-name dir-name]
         [:span
          [:span.refresh-notification.fade
           {:data-update (:refresh-counter @state)}
           "Refreshed"]
          [:button {:on-click refresh-iframe} "Refresh"]]])
      [:div.logo
       [:a {:href "/"}
        [:div
         {:dangerouslySetInnerHTML
          {:__html (rc/inline "logo.svg")}}]]
       (when (not dir-name)
         [:a.wordmark {:href "/"}
          [:div
           {:dangerouslySetInnerHTML
            {:__html (rc/inline "wordmark.svg")}}]])]])])

(defn component-main [state]
  [:div.parent
   {:on-drop #(swap! state dropped-files! %)
    :on-drag-over #(.preventDefault %)
    :on-drag-enter #(swap! state update-in [:drop-active] inc)
    :on-drag-leave #(swap! state update-in [:drop-active] dec)
    :class (when (> (:drop-active @state) 0) "drop-active")}
   [component-header state]
   (if (:started @state)
     [component-frame state]
     [:div.content
      [:section.features
       [:ul
        [:li "Live-reloading web development."]
        [:li "Runs 100% in the browser."]
        [:li "No build system required."]]]
      [:section.start
       [component-start state]
       [:p.download
        [:a {:href "livereload-template.zip"
             :download "livereload-template.zip"}
         [:button
          "Download template"]]]]
      [:p [:a {:href "https://livereload.net"} "livereload.net"]
       " is a tool that provides hot-reloading for HTML, CSS, and JS, running completely in the browser,
       eliminating the need for complex build tooling and Node.js command line. "
       "When you save your files they are auto-reloaded in the browser and you will see the change straight away."]
      [:p
       "It's great for building simple web pages and apps with HTML, JavaScript, and CSS.
       It runs 100% client side in your browser and all files stay on your local machine.
       Works great with editors like VS Code, IntelliJ, Nodepad++, Vim, PyCharm, Sublime Text, and others.
       Simply drag and drop your web project folder here to get started."]
      [:h2 "How it works"]
      [:ul
       [:li "Drag your web project folder on here, or "
        [:a {:href "#"
             :on-click (fn [ev] (activate-opener)
                         (.preventDefault ev))}
         "choose your web project folder"] "."]
       [:li "Open your project files in your text editor (e.g. index.html)."]
       [:li "Start editing and save your changes."]]
      [:p "When you change the files and save them your page will be automatically reloaded.
          Reloading is intelligent. If you modify a CSS file, only the CSS will be reloaded.
          If you modify a JS file, only the JS will be reloaded."]
      [:p "You can use this as a simple alternative to sites like Codepen and Glitch,
          but you get to use your own editor on your computer.
          All your code stays private on your computer and nothing is uploaded."]
      [:p "You can also self-host this web app on your own server.
          It is a static HTML frontend so you can simply download
          the page (right-click and 'Save As') and deploy it to your own server."]
      [:footer
       [:ul
        [:li "A web app by " [:a {:href "https://mccormick.cx"} "Chris McCormick"] "."]
        [:li "Made with " [:a {:href "https://clojurescript.org"} "ClojureScript"]  "."]
        [:li [:a {:href "https://github.com/chr15m/livereload.net"} "Source code."]]]
       [:a {:href "https://livereload.net"} [:img {:src "logo.svg"}]]]])])

; *** launch *** ;

(defn start {:dev/after-load true} []
  (rdom/render [component-main state]
               (js/document.getElementById "app")))

(defn init []
  (register-service-worker state handle-worker-message)
  (check-dir-for-changes!
    #(:file-handles @state)
    #(:files @state)
    #(swap! state update-in [:files] merge (handle-modified-files! @state %)))
  (start))
