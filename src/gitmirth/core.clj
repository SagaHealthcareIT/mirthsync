(ns gitmirth.core
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure
             [pprint :as pp]
             [zip :as zip]]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io])
  (:import java.io.File))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials, and returns the results of executing the
  supplied parameterless function for side effects"
  [base-url username password func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (client/post
     (str base-url "/users/_login")
     {:form-params
      {:username username
       :password password}
      :insecure? true})
    (func)))

(defn fetch-all
  "Fetch everything at url from remote server and return a sequence of
  channel locs based on the supplied predicates. In other words - grab
  the xml from the url via a GET request, extract the :body of the
  result, parse the XML, create a 'zipper', and return the result of
  the query (predicates)"
  [url predicates]
  (as-> url v
    (client/get v {:insecure? true})
    (:body v)
    (to-zip v)
    (apply zx/xml-> v predicates)))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path"
  [dirname xmlloc name-predicate]
  (let [name (apply zx/xml1-> xmlloc name-predicate)
        xml-str (xml/indent-str (zip/node xmlloc))
        file-path (str "blarfnarg/" dirname (File/separator) "/" name)]
    (io/make-parents file-path)
    (spit file-path xml-str)))

(defn run-channels [base-url]
  (doseq [loc (fetch-all (str base-url "/channels") [:list :channel])]
    (serialize-node "channels" loc [:name zip/down zip/node])))

(defn run-codeTemplates [base-url]
  (doseq [loc (fetch-all (str base-url "/codeTemplates") [:list :codeTemplate])]
    (serialize-node "codeTemplates" loc [:name zip/down zip/node])))

(defn makeitso
  "App logic"
  [base-url username password]
  (with-authentication base-url username password
    (fn [] (do
            (run-channels base-url)
            (run-codeTemplates base-url)))))

(defn -main
  [& args]
  (println "starting")
  (pp/pprint (makeitso "https://misc:8443/api" "admin" "admin"))
  (println "finished"))
