(defproject com.saga-it/mirthsync "0.1.0-SNAPSHOT"
  :description "Mirthsync is a command line tool for keeping a local
  copy of important aspects of Mirth Connect configuration in order to
  allow for the use of traditional version control tools like Git or
  SVN. Downloading and uploading to a remote Mirth server are both
  supported. The only requirements are having credentials for the
  server that is being synced and the server also needs to support and
  allow access to the REST API."
  :url "https://github.com/SagaHealthcareIT/mirthsync"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.3.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]]
  :main mirthsync.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
