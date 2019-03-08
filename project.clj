(defproject com.saga-it/mirthsync "2.0.2-SNAPSHOT"
  :description "Mirthsync is a command line tool, created by Saga IT,
  for keeping a local copy of important aspects of Mirth Connect
  configuration in order to allow for the use of traditional version
  control tools like Git or SVN. With Mirthsync you are able to
  selectively pull the code for channels, groups, and more from a
  local or remote Mirth Connect instance and have the code placed into
  a local hierarchy of files and directories that can be tracked using
  version control. Selectively pushing code to local or remote Mirth
  Connect servers is also possible using Mirthsync.

  The only requirements are having credentials for the
  server that is being synced and ensuring that the server is
  configured to allow access to its REST API."
  :url "https://github.com/SagaHealthcareIT/mirthsync"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [clj-http "3.9.1" :exclusions [commons-logging]]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]
                 [org.clojure/tools.cli "0.4.1"]
                 ;; [tolitius/xml-in "0.1.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [ch.qos.logback/logback-classic "1.1.7"]
                 [ch.qos.logback/logback-core "1.1.7"]
                 [org.slf4j/slf4j-api "1.7.22"]

                 ;; logging redirects
                 [org.slf4j/jcl-over-slf4j "1.7.22"]
                 [org.slf4j/log4j-over-slf4j "1.7.22"]
                 [org.apache.logging.log4j/log4j-to-slf4j "2.7"]
                 [org.slf4j/osgi-over-slf4j "1.7.22"]
                 [org.slf4j/jul-to-slf4j "1.7.22"]

                 ;;;; don't need this for now
                 ;; [com.fasterxml.jackson.core/jackson-core "2.9.6"]
                 ;; [com.fasterxml.jackson.dataformat/jackson-dataformat-xml "2.9.6"]
                 ;; [com.fasterxml.jackson.core/jackson-databind "2.9.6"]
                 ]
  ;; :exclusions [commons-logging
  ;;              log4j
  ;;              org.apache.logging.log4j/log4j
  ;;              org.slf4j/simple
  ;;              org.slf4j/slf4j-jcl
  ;;              org.slf4j/slf4j-nop
  ;;              org.slf4j/slf4j-log4j12
  ;;              org.slf4j/slf4j-log4j13]
  
  :plugins [[lein-ancient "0.6.15"]
            [lein-tar "3.3.0"]]
  :main mirthsync.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :omit-source true}
             :repl {:plugins [[cider/cider-nrepl "0.21.1"]]}}
  :release-tasks [["clean"]
                  ["test"]
                  ["vcs" "assert-committed"]
                  ;; ;; bump minor
                  ;; ["change" "version" "leiningen.release/bump-version"]
                  ;; ;; bump major
                  ;; ;; ["change" "version"
                  ;; ;;  "leiningen.release/bump-version" "release"]
                  ;; ["vcs" "commit"]
                  ;; ;; ["vcs" "tag" "--no-sign"]
                  ["tar"]]
  :tar {:uberjar true
        :format :tar-gz})
