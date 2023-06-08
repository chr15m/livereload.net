(ns livereload.watch
  (:require
    [promesa.core :as p]
    [applied-science.js-interop :as j]))

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
          file-handle-promises (.map (js/Array.from handles)
                                     (fn [[_filename handle]]
                                       (when (and handle (j/get handle :getFile))
                                         (.getFile handle))))
          files (js/Promise.all file-handle-promises)]
    (.filter files identity)))

(defn get-files-from-event [ev]
  (-> ev
      (j/get-in [:target :files])
      (js/Array.from)))

(defn compare-file-last-modified [files known-files]
  ; chrome can simply check the last modified using the new files API
  (p/let [changed-files (js/Promise.all
                          (.map files
                                (fn [f]
                                  (let [fname (j/get f :name)]
                                    (when (not= (j/get f :lastModified)
                                                (:lastModified (get known-files fname)))
                                      [fname (j/assoc! (j/select-keys f [:lastModified :size :type :name])
                                                       :actual-file f)])))))
          changed-files (js->clj changed-files :keywordize-keys true)]
    (into {} changed-files)))

(defn compare-file-contents [files known-files]
  ; on firefox we have to get content and compare it because all other fields are frozen
  (p/let [files-struct (p/all (for [f files]
                                (p/let [content (j/call f :text)
                                        webkit-path (j/get f :webkitRelativePath)
                                        fname (-> webkit-path (.split "/") (.slice 1) (.join "/"))]
                                  [fname
                                   (js->clj
                                     (j/assoc! (j/select-keys f [:lastModified :size :type :name])
                                               :actual-file f
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

(defn check-dir-for-changes! [fn-get-file-handles fn-get-known-files fn-callback]
  (p/catch
    (p/let [{:keys [source files dir-handle]} (fn-get-file-handles)
            known-files (fn-get-known-files)
            modified-files (case source
                             :picker
                             (p/let [files (get-files-from-picker dir-handle)]
                               (compare-file-last-modified (js/Array.from files) known-files))
                             :input
                             (compare-file-contents files known-files)
                             {})]
      (fn-callback modified-files)
      (js/setTimeout #(check-dir-for-changes! fn-get-file-handles fn-get-known-files fn-callback) 250))
    (fn [err]
      (js/console.error err)
      (js/setTimeout #(check-dir-for-changes! fn-get-file-handles fn-get-known-files fn-callback) 2000))))
