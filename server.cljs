(ns server
  (:require
    ["fs" :as fs]
    [promesa.core :as p]
    [sitefox.web :as web]))

(defn setup-routes [app]
  (web/reset-routes app)
  (web/static-folder app "/" (if (fs/existsSync "public") "public" ".")))

(defonce init
  (p/let [[app host port] (web/start)]
    (setup-routes app)
    (print "Serving at" (str host ":" port))))
