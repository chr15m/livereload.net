(ns livereload.watch
  (:require
    [clojure.string :refer [join]]
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

(defn get-files-from-picker [dir-handle dir-names]
  (p/let [entries (j/call dir-handle :entries)
          handles (clj->js (unpack-entries entries []))
          file-handle-promises (.map (js/Array.from handles)
                                     (fn [[_filename handle]]
                                       (cond
                                         (and handle (j/get handle :getFile))
                                         (p/let [file (.getFile handle)
                                                 fname (j/get file :name)
                                                 fname (join "/" (conj dir-names fname))]
                                           #js [(j/assoc! file :fullpath fname)])
                                         (and handle (j/get handle :getDirectoryHandle))
                                         (get-files-from-picker handle (conj dir-names (j/get handle :name))))))
          files (js/Promise.all file-handle-promises)
          files (clj->js (apply concat files))]
    (.filter files identity)))

(defn get-files-from-event [ev]
  (-> ev
      (j/get-in [:target :files])
      (js/Array.from)))

(defn get-files-from-fs-entry [fs-entry dir-names]
  (let [reader (.createReader fs-entry)
        entries-promise (p/promisify #(.readEntries reader %))]
  (p/let [entries (entries-promise)
          file-entry-promises (.map (js/Array.from entries)
                                     (fn [item]
                                       (cond
                                         (and item (j/get item :isFile))
                                         (let [file-promise (p/promisify #(.file item %1))]
                                           (p/let [file (file-promise)
                                                   fname (j/get file :name)
                                                   fname (join "/" (conj dir-names fname))]
                                             #js [(j/assoc! file :fullpath fname)]))
                                         (and item (j/get item :isDirectory))
                                         (get-files-from-fs-entry item (conj dir-names (j/get item :name))))))
          files (js/Promise.all file-entry-promises)
          files (clj->js (apply concat files))]
    (.filter files identity))))


(defn compare-file-last-modified [files known-files]
  ; chrome can simply check the last modified using the new files API
  (p/let [changed-files (js/Promise.all
                          (.map files
                                (fn [f]
                                  (let [fname (j/get f :fullpath)]
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
                                        fname (j/get f :webkitRelativePath)]
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
    (p/let [{:keys [source files dir-handle fs-entry]} (fn-get-file-handles)
            known-files (fn-get-known-files)
            modified-files (case source
                             :picker
                             (p/let [files (get-files-from-picker dir-handle [(j/get dir-handle :name)])]
                               (compare-file-last-modified (js/Array.from files) known-files))
                             :input
                             (compare-file-contents files known-files)
                             :dropped
                             (p/let [files (get-files-from-fs-entry fs-entry [(j/get fs-entry :name)])]
                               (compare-file-last-modified (js/Array.from files) known-files))
                             {})]
      (fn-callback modified-files)
      (js/setTimeout #(check-dir-for-changes! fn-get-file-handles fn-get-known-files fn-callback) 250))
    (fn [err]
      (js/console.error err)
      (js/setTimeout #(check-dir-for-changes! fn-get-file-handles fn-get-known-files fn-callback) 2000))))
