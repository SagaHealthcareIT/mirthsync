(ns mirthsync.core-test
  (:require [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [mirthsync.actions :as actions]
            [mirthsync.core :as core]
            [mirthsync.git :as git]
            [mirthsync.http-client :as http]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as xml]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private channel-xml
  "<channel><id>channel-1</id><name>Channel</name></channel>")

(def ^:private template-xml
  "<codeTemplate><id>template-1</id><name>Template</name></codeTemplate>")

(def ^:private library-xml
  "<codeTemplateLibrary><id>library-1</id><name>Library</name><codeTemplates/></codeTemplateLibrary>")

(def ^:private template-status-xml
  "<list><dashboardStatus><channelId>channel-1</channelId><deployedRevisionDelta>0</deployedRevisionDelta><codeTemplatesChanged>true</codeTemplatesChanged></dashboardStatus></list>")

(def ^:private changed-status-xml
  "<list><dashboardStatus><channelId>channel-1</channelId><deployedRevisionDelta>1</deployedRevisionDelta><codeTemplatesChanged>false</codeTemplatesChanged></dashboardStatus></list>")

(defn- run-push
  "Exercise CLI parsing, file selection, uploads, and deployment with HTTP stubs."
  [files flags {:keys [fail-at responses statuses action]
                :or {responses {} statuses changed-status-xml action "push"}}]
  (let [target (.toFile (Files/createTempDirectory "mirthsync-core-test-" (make-array FileAttribute 0)))
        requests (atom [])
        commits (atom [])
        uploads (atom [])
        upload actions/upload
        fail! (fn [stage]
                (when (= fail-at stage)
                  (throw+ {:status 500 :body (str "Failure at " (name stage))})))
        response (fn [api]
                   (get responses api
                        (case api
                          :server-configuration {:status 204 :body nil}
                          :code-template-libraries {:status 200 :body "<result><overrideNeeded>false</overrideNeeded><librariesSuccess>true</librariesSuccess></result>"}
                          {:status 200 :body "true"})))]
    (try
      (doseq [[path content] files]
        (let [file (io/file target path)]
          (io/make-parents file)
          (spit file content)))
      (with-redefs [http/with-authentication (fn [_ f] (fail! :authentication) (f))
                    actions/upload (fn [conf]
                                     (let [result (upload conf)]
                                       (swap! uploads conj (select-keys result [:api :exit-code :code-templates-pushed]))
                                       result))
                    http/fetch-all (fn [conf find-elements]
                                     (fail! :preprocessing)
                                     (find-elements (xml/to-zip (if (= :channel-groups (:api conf)) "<set/>" "<list/>"))))
                    http/get-xml (fn [_ path]
                                   (swap! requests conj {:method :get :path path})
                                   (fail! :statuses)
                                   statuses)
                    http/put-xml (fn [conf _]
                                   (swap! requests conj {:method :put :api (:api conf)
                                                         :id (mi/find-id (:api conf) (:el-loc conf))})
                                   (fail! :save)
                                   (response (:api conf)))
                    http/post-xml (fn [conf path body _ _]
                                    (swap! requests conj {:method :post :path path :body body})
                                    (if (= path "/channels/_deploy")
                                      (do (fail! :deployment) {:status 204 :body nil})
                                      (do (fail! :save) (response (:api conf)))))
                    git/auto-commit-after-operation (fn [conf] (swap! commits conj conf))]
        {:exit-code (apply core/main-func
                           (concat ["-s" "https://test.invalid/api" "-u" "admin" "-p" "test"
                                    "-t" (.getPath target) "--auto-commit"] flags [action]))
         :requests @requests
         :uploads @uploads
         :commits @commits})
      (finally
        (doseq [file (reverse (file-seq target))]
          (io/delete-file file))))))

(defn- deploy-requests [result]
  (filter #(= "/channels/_deploy" (:path %)) (:requests result)))

(deftest deployment-failures-reach-cli
  (doseq [[flags failure-stage]
          [[["--deploy-changed"] :statuses]
           [["--deploy-changed"] :deployment]]]
    (testing (str flags " failure at " failure-stage)
      (let [result (run-push {"Channels/Default Group/Channel.xml" channel-xml}
                             flags {:fail-at failure-stage})]
        (is (= 1 (:exit-code result)))
        (is (empty? (:commits result)))
        (is (= (if (= :statuses failure-stage) 0 1) (count (deploy-requests result)))))))

  (testing "Malformed status XML fails the command without committing or deploying"
    (let [result (run-push {} ["--deploy-changed" "--deploy-new"] {:statuses "<list>"})]
      (is (= 1 (:exit-code result)))
      (is (empty? (:commits result)))
      (is (empty? (deploy-requests result))))))

(deftest deployment-prerequisite-failures
  (doseq [stage [:authentication :preprocessing :save]]
    (testing (str "No deployment or auto-commit after " stage " fails")
      (let [result (run-push {"Channels/Default Group/Channel.xml" channel-xml}
                             ["--deploy-changed"] {:fail-at stage})]
        (is (= 1 (:exit-code result)))
        (is (empty? (:commits result)))
        (is (empty? (deploy-requests result))))))

  (testing "A rejected save is not hidden by subsequent successful API processing"
    (let [result (run-push {"Channels/Default Group/Channel.xml" channel-xml}
                           ["--deploy-changed"]
                           {:responses {:channels {:status 200 :body "false"}}})]
      (is (= 1 (:exit-code result)))
      (is (not-any? #(= :get (:method %)) (:requests result)))
      (is (empty? (deploy-requests result))))))

(deftest successful-deployment-and-noop
  (doseq [flags [["--deploy-changed"]]]
    (testing (str "Successful deployment keeps exit code zero: " flags)
      (let [result (run-push {"Channels/Default Group/Channel.xml" channel-xml} flags {})]
        (is (= 0 (:exit-code result)))
        (is (= 1 (count (:commits result))))
        (is (= 1 (count (deploy-requests result)))))))

  (doseq [flags [["--deploy-changed"]]]
    (testing "An empty deployment selection is a successful no-op"
      (let [result (run-push {} flags {:statuses "<list/>"})]
        (is (= 0 (:exit-code result)))
        (is (= 1 (count (:commits result))))
        (is (empty? (deploy-requests result)))))))

(deftest template-deployment-requires-successful-upload
  (doseq [[label files flags]
          [["empty repository" {} []]
           ["channel-only repository" {"Channels/Default Group/Channel.xml" channel-xml} []]
           ["restricted channel push"
            {"Channels/Default Group/Channel.xml" channel-xml
             "CodeTemplates/Library/Template.xml" template-xml
             "CodeTemplates/Library/index.xml" library-xml}
            ["--restrict-to-path" "Channels"]]]]
    (testing label
      (let [result (run-push files (into ["--deploy-changed"] flags) {:statuses template-status-xml})]
        (is (= 0 (:exit-code result)))
        (is (empty? (deploy-requests result)))
        (is (not (:code-templates-pushed (first (:commits result))))))))

  (doseq [[files flags]
          [[{"CodeTemplates/Library/Template.xml" template-xml} ["--disk-mode" "code"]]
           [{"CodeTemplates/Library/Template.xml" template-xml} ["--disk-mode" "items"]]
           [{"CodeTemplates/Library/index.xml" library-xml} ["--disk-mode" "groups"]]
           [{"FullBackup.xml" "<serverConfiguration><codeTemplateLibraries/></serverConfiguration>"} ["--disk-mode" "backup"]]
           [{"FullBackup.xml" "<serverConfiguration><codeTemplates/></serverConfiguration>"} ["--disk-mode" "backup"]]]]
    (testing (str "Successful template/library save triggers deployment: " flags)
      (let [result (run-push files (into ["--deploy-changed"] flags) {:statuses template-status-xml})
            deployment (first (deploy-requests result))]
        (is (= 0 (:exit-code result)))
        (is (true? (:code-templates-pushed (first (:commits result)))))
        (is (= 1 (count (deploy-requests result))))
        (when deployment
          (is (= ["channel-1"] (vec (zx/xml-> (xml/to-zip (:body deployment)) :string zx/text)))))
        (is (= :get (:method (nth (:requests result) (- (count (:requests result)) 2))))))))

  (testing "A backup without a template section does not imply templates were uploaded"
    (let [result (run-push {"FullBackup.xml" "<serverConfiguration><globalScripts/></serverConfiguration>"}
                           ["--deploy-changed" "--disk-mode" "backup"] {:statuses template-status-xml})]
      (is (= 0 (:exit-code result)))
      (is (empty? (deploy-requests result)))
      (is (not (:code-templates-pushed (first (:commits result)))))))

  (doseq [[files flags responses]
          [[{"CodeTemplates/Library/Template.xml" template-xml} []
            {:code-templates {:status 200 :body "false"}}]
           [{"CodeTemplates/Library/index.xml" library-xml} ["--disk-mode" "groups"]
            {:code-template-libraries {:status 200 :body "<result><overrideNeeded>true</overrideNeeded><librariesSuccess>false</librariesSuccess></result>"}}]
           [{"FullBackup.xml" "<serverConfiguration><codeTemplateLibraries/></serverConfiguration>"}
            ["--disk-mode" "backup"] {:server-configuration {:status 500 :body "error"}}]]]
    (testing "Rejected template saves do not trigger selective deployment"
      (let [result (run-push files (into ["--deploy-changed"] flags)
                             {:statuses template-status-xml :responses responses})]
        (is (= 1 (:exit-code result)))
        (is (empty? (deploy-requests result)))
        (is (not-any? :code-templates-pushed (:uploads result)))))))

(deftest template-tracking-persists-within-one-run
  (testing "Later non-template uploads preserve successful template tracking"
    (let [files {"CodeTemplates/Library/Template.xml" template-xml
                 "Channels/Default Group/Channel.xml" channel-xml}
          result (run-push files ["--deploy-changed"] {:statuses template-status-xml})]
      (is (= 0 (:exit-code result)))
      (is (true? (:code-templates-pushed (first (:commits result)))))
      (is (= [:put :put :get :post] (mapv :method (:requests result))))))

  (testing "A partial push failure suppresses deployment despite earlier template success"
    (let [result (run-push {"CodeTemplates/Library/Template.xml" template-xml
                            "Channels/Default Group/Channel.xml" channel-xml}
                           ["--deploy-changed"]
                           {:statuses template-status-xml :responses {:channels {:status 200 :body "false"}}})]
      (is (= 1 (:exit-code result)))
      (is (empty? (deploy-requests result)))))

  (testing "Template tracking does not leak between CLI invocations"
    (let [first-run (run-push {"CodeTemplates/Library/Template.xml" template-xml}
                              ["--deploy-changed"] {:statuses template-status-xml})
          second-run (run-push {} ["--deploy-changed"] {:statuses template-status-xml})]
      (is (= 1 (count (deploy-requests first-run))))
      (is (empty? (deploy-requests second-run))))))

(deftest deployment-flag-interactions
  (testing "Deploy-new still deploys a saved, undeployed channel after a successful push"
    (let [result (run-push {"Channels/Default Group/Channel.xml" channel-xml}
                           ["--deploy-new" "--deploy-changed"] {:statuses "<list/>"})]
      (is (= 0 (:exit-code result)))
      (is (= [:put :get :post] (mapv :method (:requests result))))
      (is (= 1 (count (deploy-requests result)))))))

(deftest selective-deployment-is-push-only
  (testing "Deploy-new alone does not enable selective deployment"
    (let [result (run-push {"Channels/Default Group/Channel.xml" channel-xml} ["--deploy-new"] {})]
      (is (= 0 (:exit-code result)))
      (is (= [:put] (mapv :method (:requests result))))))

  (testing "Pull ignores deployment flags and still auto-commits on success"
    (let [result (run-push {} ["--deploy-all" "--deploy-changed" "--deploy-new"] {:action "pull"})]
      (is (= 0 (:exit-code result)))
      (is (empty? (:requests result)))
      (is (= 1 (count (:commits result)))))))
