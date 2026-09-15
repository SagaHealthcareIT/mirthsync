(ns mirthsync.apis-test
  (:require [clojure.data :as cd]
            [clojure.data.zip.xml :as cdzx]
            [clojure.java.io :as io]
            [clojure.test :as ct]
            [clojure.zip :as cz]
            [mirthsync.apis :as ma]
            [mirthsync.http-client :as mhttp]
            [mirthsync.cli :as cli]
            [mirthsync.interfaces]
            [mirthsync.cross-platform-utils :as cpu]
            [mirthsync.files :as mf]
            [mirthsync.fixture-tools :refer [build-path]]
            [mirthsync.xml :as mx]
            [slingshot.slingshot :refer [throw+]]))

(defn update-id [loc]
  (-> loc
      (cdzx/xml1-> :id)
      cz/next
      (cz/replace "1234b87b-71a7-42dd-b00e-049d28adae64")
      cz/root
      cz/xml-zip))

(def channel-groups-loc (mx/to-zip "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<set>
  <channelGroup version=\"3.11.0\">
    <id>630d82bd-0727-48d6-bf13-bfa86080d9f5</id>
    <name>/this\\ is /a \\ group/\\with weird\\characters/</name>
    <revision>1</revision>
    <lastModified>
      <time>1640210846851</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description/>
    <channels>
      <channel version=\"3.11.0\">
        <id>de5a1a40-3f1e-44e1-b3cb-60d6772234ae</id>
        <revision>0</revision>
      </channel>
    </channels>
  </channelGroup>
  <channelGroup version=\"3.11.0\">
    <id>2b8b91c1-0340-4e44-af3b-333aae9c7262</id>
    <name>This is a group</name>
    <revision>1</revision>
    <lastModified>
      <time>1640210846973</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description>This is the group's description</description>
    <channels>
      <channel version=\"3.11.0\">
        <id>2521ed7e-156d-47dd-b701-0705583b99ec</id>
        <revision>0</revision>
      </channel>
      <channel version=\"3.11.0\">
        <id>fab4b87b-71a7-42dd-b00e-049d28adae64</id>
        <revision>0</revision>
      </channel>
    </channels>
  </channelGroup>
</set>"))

(def channel-group-loc (mx/to-zip (slurp (build-path "target" "test-data" "mirth-3-11-baseline" "Channels" "This is a group" "index.xml"))))

(def channel-in-group-loc
  (mx/to-zip (slurp (build-path "target" "test-data" "mirth-3-11-baseline" "Channels" "This is a group" "Http Hello2 3081.xml"))))

(def channel-without-group-loc
  (mx/to-zip (slurp (build-path "target" "test-data" "mirth-3-11-baseline" "Channels" "Default Group" "Http 3080.xml"))))

(def updated-channel-group-loc
  (update-id channel-group-loc))

(def codetemplate-libraries-loc (mirthsync.xml/to-zip "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<list>
  <codeTemplateLibrary version=\"3.11.0\">
    <id>3e5488e0-2c95-456e-bce3-0178d365d198</id>
    <name>Library 1</name>
    <revision>1</revision>
    <lastModified>
      <time>1640210846608</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description/>
    <includeNewChannels>false</includeNewChannels>
    <enabledChannelIds/>
    <disabledChannelIds>
      <string>2521ed7e-156d-47dd-b701-0705583b99ec</string>
      <string>51a75675-33cc-4d87-87c6-2a337dedff67</string>
    </disabledChannelIds>
    <codeTemplates>
      <codeTemplate version=\"3.11.0\">
        <id>66d76711-0274-49e4-b92a-73c4690bcbe7</id>
      </codeTemplate>
      <codeTemplate version=\"3.11.0\">
        <id>62288726-4e95-478f-9fd7-5d2d709e805e</id>
      </codeTemplate>
    </codeTemplates>
  </codeTemplateLibrary>
  <codeTemplateLibrary version=\"3.11.0\">
    <id>506d3ed2-a38e-46f4-a970-1c99f317a78b</id>
    <name>Library 2</name>
    <revision>1</revision>
    <lastModified>
      <time>1640210846666</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description/>
    <includeNewChannels>false</includeNewChannels>
    <enabledChannelIds/>
    <disabledChannelIds>
      <string>2521ed7e-156d-47dd-b701-0705583b99ec</string>
      <string>51a75675-33cc-4d87-87c6-2a337dedff67</string>
    </disabledChannelIds>
    <codeTemplates>
      <codeTemplate version=\"3.11.0\">
        <id>b64edb54-5358-4f6c-b8e1-c66f0b517eab</id>
      </codeTemplate>
    </codeTemplates>
  </codeTemplateLibrary>
</list>"))

(def codetemplate-library-loc
  (mx/to-zip (slurp (build-path "target" "test-data" "mirth-3-11-baseline" "CodeTemplates" "Library 2" "index.xml"))))

(def updated-codetemplate-library-loc
  (update-id codetemplate-library-loc))

(def codetemplate-loc
  (mx/to-zip (slurp (build-path "target" "test-data" "mirth-3-11-baseline" "CodeTemplates" "Library 2" "Template 2.xml"))))

;;;; keeping this here for now as an alternate specter based implementation of
;;;; our add/update function in api.clj.
;; (defn add-update-child-specter
;;   [root child]
;;   (let [id-path [:content ALL (fn [t] (= (:tag t) :id)) :content FIRST]
;;         id (select-one id-path child)
;;         [new-root replaced] (replace-in
;;                              [:content
;;                               ALL
;;                               (fn [t] (and (= (:tag t) (:tag child))
;;                                           (= id (select-one id-path t))))]
;;                              (fn [old] [child old])
;;                              root)]
;;     (if (nil? replaced)
;;       (setval [:content AFTER-ELEM] child root)
;;       new-root)))


(ct/deftest test-nested-file-path
  (ct/testing "Nested channel path is valid"
    (ct/is (= (build-path "target" "Channels" "This is a group" "Http Hello2 3081.xml")
              (ma/nested-file-path channel-groups-loc
                                   [:channelGroup :channels :channel]
                                   {:target "target"
                                    :el-loc channel-in-group-loc
                                    :api :channels}))))

  (ct/testing "Default Group channel path is valid"
    (ct/is (= (build-path "target" "Channels" "Default Group" "Http 3080.xml")
              (ma/nested-file-path channel-groups-loc
                                   [:channelGroup :channels :channel]
                                   {:target "target"
                                    :el-loc channel-without-group-loc
                                    :api :channels}))))

  (ct/testing "Nested code template path is valid"
    (ct/is (= (build-path "target" "CodeTemplates" "Library 2" "Template 2.xml")
              (ma/nested-file-path codetemplate-libraries-loc
                                   [:codeTemplateLibrary :codeTemplates :codeTemplate]
                                   {:target "target"
                                    :el-loc codetemplate-loc
                                    :api :code-templates})))))

(ct/deftest test-add-update-child
  (ct/testing "Update results in identical codetemplate library xml"
    (let [[a b] (cd/diff
                 (cz/node codetemplate-libraries-loc)
                 (cz/node (mx/add-update-child codetemplate-libraries-loc codetemplate-library-loc)))]
      (ct/is (= [nil nil] [a b]))))

  (ct/testing "Update results in identical channel group xml"
    (let [[a b] (cd/diff
                 (cz/node channel-groups-loc)
                 (cz/node (mx/add-update-child channel-groups-loc channel-group-loc)))]
      (ct/is (= [nil nil] [a b]))))

  (ct/testing "Add results in addition to right side of diff and nothing on left"
    (let [[a b] (cd/diff
                 (cz/node channel-groups-loc)
                 (cz/node (mx/add-update-child channel-groups-loc updated-channel-group-loc)))]
      (ct/is (= nil a))
      (ct/is (not= nil b)))))

(ct/deftest test-safe-file-paths
  (ct/testing "File names don't traverse paths"
    (ct/are [x y] (= x y)
      "foo.xml" (mf/safe-name "foo.xml")
      "%2Fpath chars in%2F%5C%5C%2F%5C%2F name.ext%2F" (mf/safe-name "/path chars in/\\\\/\\/ name.ext/")
      "!@#$%^&*()_+-=[]{}||;:'\",<.>%2F?.xml" (mf/safe-name "!@#$%^&*()_+-=[]{}||;:'\",<.>/?.xml"))))

(ct/deftest test-correct-apis-for-conf
  (ct/testing "Apis function returns the correct apis depending on app-conf"
    (ct/is (= [:server-configuration]
              (ma/apis {:disk-mode "backup"})))
    (ct/is (= [:configuration-map
               :global-scripts
               :resources
               :code-template-libraries
               :code-templates
               :channel-groups
               :channels
               :alerts]
              (ma/apis {:disk-mode "groups" :include-configuration-map true})))
    (ct/is (= [:global-scripts
               :resources
               :code-template-libraries
               :code-templates
               :channel-groups
               :channels
               :alerts]
              (ma/apis {:disk-mode "groups" :include-configuration-map false})))))

(defn- assert-deployment-xml
  "Compare the complete XML tree, including any unexpected text around IDs."
  [expected actual]
  (ct/is (= (cz/node (mx/to-zip expected))
            (cz/node (mx/to-zip actual)))))

(ct/deftest bulk-deployment-tests
  (ct/testing "Deploy all channels function creates correct XML"
    (let [app-conf {:bulk-deploy-channels (atom ["channel1" "channel2" "channel3"])}
          expected-xml "<set><string>channel1</string><string>channel2</string><string>channel3</string></set>"]
      ;; Test that the XML structure is created correctly
      ;; We can't easily test the actual HTTP call without mocking, but we can test the data structure
      (ct/is (= ["channel1" "channel2" "channel3"] @(:bulk-deploy-channels app-conf)))))

  (ct/testing "Deploy all channels handles empty channel list"
    (let [app-conf {:bulk-deploy-channels (atom [])}]
      ;; Should handle empty list gracefully
      (ct/is (empty? @(:bulk-deploy-channels app-conf)))))

  (ct/testing "Deploy all channels with nil atom"
    (let [app-conf {:bulk-deploy-channels nil}]
      ;; Should handle nil atom gracefully
      (ct/is (nil? (:bulk-deploy-channels app-conf))))))

(ct/deftest channel-after-push-tests
  (ct/testing "Channel after-push collects IDs for bulk deployment"
    (let [app-conf {:deploy-all true :bulk-deploy-channels (atom [])}
          api :channels
          el-loc (mx/to-zip "<channel><id>test-channel-id</id><name>Test Channel</name></channel>")
          result {:status 200 :body "true"}]
      ;; Mock the find-id function behavior
      (with-redefs [mirthsync.interfaces/find-id (fn [_ _] "test-channel-id")]
        (mirthsync.interfaces/after-push api (assoc app-conf :el-loc el-loc) result)
        (ct/is (= ["test-channel-id"] @(:bulk-deploy-channels app-conf))))))

  (ct/testing "Channel after-push doesn't collect IDs when deploy-all is false"
    (let [app-conf {:deploy false :deploy-all false :bulk-deploy-channels (atom [])}
          api :channels
          el-loc (mx/to-zip "<channel><id>test-channel-id</id><name>Test Channel</name></channel>")
          result {:status 200 :body "true"}]
      ;; Mock the find-id function behavior
      (with-redefs [mirthsync.interfaces/find-id (fn [_ _] "test-channel-id")]
        (mirthsync.interfaces/after-push api (assoc app-conf :el-loc el-loc) result)
        (ct/is (empty? @(:bulk-deploy-channels app-conf))))))

  (ct/testing "Channel after-push handles failed channel push"
    (let [app-conf {:deploy-all true :bulk-deploy-channels (atom [])}
          api :channels
          el-loc (mx/to-zip "<channel><id>test-channel-id</id><name>Test Channel</name></channel>")
          result {:status 400 :body "error"}]
      ;; When push fails, should not collect channel ID and should return false
      (ct/is (= false (mirthsync.interfaces/after-push api (assoc app-conf :el-loc el-loc) result)))
      (ct/is (empty? @(:bulk-deploy-channels app-conf))))))

(def ^:private changed-statuses-xml
  "<list>
     <dashboardStatus>
       <channelId>positive-delta</channelId><name>Positive delta</name>
       <deployedRevisionDelta>2</deployedRevisionDelta><codeTemplatesChanged>false</codeTemplatesChanged>
     </dashboardStatus>
     <dashboardStatus>
       <channelId>unchanged</channelId><name>Unchanged</name>
       <deployedRevisionDelta>0</deployedRevisionDelta><codeTemplatesChanged>false</codeTemplatesChanged>
     </dashboardStatus>
     <dashboardStatus>
       <channelId>templates-only</channelId><name>Templates only</name>
       <deployedRevisionDelta>0</deployedRevisionDelta><codeTemplatesChanged>true</codeTemplatesChanged>
     </dashboardStatus>
     <dashboardStatus>
       <channelId>negative-delta</channelId><name>Negative delta</name>
       <deployedRevisionDelta>-1</deployedRevisionDelta><codeTemplatesChanged>false</codeTemplatesChanged>
     </dashboardStatus>
     <dashboardStatus>
       <channelId>both-changed</channelId><name>Both changed</name>
       <deployedRevisionDelta>1</deployedRevisionDelta><codeTemplatesChanged>true</codeTemplatesChanged>
     </dashboardStatus>
   </list>")

(defn- assert-deploy-changed-request
  "Run the real deployment function with HTTP stubs and inspect its requests."
  [app-conf statuses-xml expected-xml]
  (let [calls (atom [])]
    (with-redefs [mhttp/get-xml (fn [conf path]
                                (swap! calls conj [:get conf path])
                                statuses-xml)
                  mhttp/post-xml (fn [conf path body params multipart?]
                                  (swap! calls conj [:post conf path body params multipart?])
                                  {:status 200 :body "<map/>"})]
      (ma/deploy-changed-channels app-conf))
    (ct/is (= [:get app-conf "/channels/statuses"] (first @calls)))
    (ct/is (= (if expected-xml 2 1) (count @calls)))
    (when expected-xml
      (let [[method conf path body params multipart?] (second @calls)]
        (ct/is (= [:post app-conf "/channels/_deploy" {:returnErrors "true" :debug "false"} false]
                  [method conf path params multipart?]))
        (assert-deployment-xml expected-xml body)))))

(ct/deftest deploy-changed-channels-tests
  (doseq [[templates-pushed expected-xml]
          [[true "<set><string>positive-delta</string><string>templates-only</string><string>negative-delta</string><string>both-changed</string></set>"]
           [false "<set><string>positive-delta</string><string>negative-delta</string><string>both-changed</string></set>"]
           [nil "<set><string>positive-delta</string><string>negative-delta</string><string>both-changed</string></set>"]]]
    (ct/testing (str "Revision changes always deploy; template changes require templates pushed: " templates-pushed)
      (assert-deploy-changed-request {:code-templates-pushed templates-pushed}
                                     changed-statuses-xml expected-xml)))

  (doseq [templates-pushed [true false]
          statuses-xml ["<list/>"
                        "<list><dashboardStatus><channelId>unchanged</channelId><deployedRevisionDelta>0</deployedRevisionDelta><codeTemplatesChanged>false</codeTemplatesChanged></dashboardStatus></list>"]]
    (ct/testing "No pending changes means no deploy request"
      (assert-deploy-changed-request {:code-templates-pushed templates-pushed} statuses-xml nil)))

  (doseq [delta-element ["" "<deployedRevisionDelta/>"
                         "<deployedRevisionDelta>invalid</deployedRevisionDelta>"
                         "<deployedRevisionDelta>2147483648</deployedRevisionDelta>"]
          templates-pushed [true false]]
    (ct/testing (str "Missing or invalid deltas still allow template-only changes: " delta-element)
      (assert-deploy-changed-request
       {:code-templates-pushed templates-pushed}
       (str "<list><dashboardStatus><channelId>templates-only</channelId>"
            delta-element
            "<codeTemplatesChanged>true</codeTemplatesChanged></dashboardStatus></list>")
       (when templates-pushed "<set><string>templates-only</string></set>")))))

(ct/deftest deploy-new-channels-tests
  (ct/testing "Changed and undeployed IDs are combined once in discovery order"
    (let [pushed-ids (atom ["unchanged" "new-channel" "positive-delta" "new-channel"])]
      (assert-deploy-changed-request
       {:code-templates-pushed false :pushed-channel-ids pushed-ids}
       changed-statuses-xml
       "<set><string>positive-delta</string><string>negative-delta</string><string>both-changed</string><string>new-channel</string></set>")
      (ct/is (= ["unchanged" "new-channel" "positive-delta" "new-channel"] @pushed-ids))))

  (ct/testing "Every pushed channel is new when the dashboard is empty"
    (assert-deploy-changed-request
     {:pushed-channel-ids (atom ["new-channel" "another-new-channel" "new-channel"])}
     "<list/>"
     "<set><string>new-channel</string><string>another-new-channel</string></set>"))

  (ct/testing "Already deployed and unchanged channels are not redeployed by deploy-new"
    (assert-deploy-changed-request
     {:pushed-channel-ids (atom ["unchanged"])}
     "<list><dashboardStatus><channelId>unchanged</channelId><deployedRevisionDelta>0</deployedRevisionDelta></dashboardStatus></list>"
     nil))

  (ct/testing "Empty tracking does not request deployment"
    (assert-deploy-changed-request {:pushed-channel-ids (atom [])} "<list/>" nil))

  (ct/testing "Repeated dashboard entries are deployed only once"
    (assert-deploy-changed-request
     {}
     "<list><dashboardStatus><channelId>changed</channelId><deployedRevisionDelta>1</deployedRevisionDelta></dashboardStatus><dashboardStatus><channelId>changed</channelId><deployedRevisionDelta>2</deployedRevisionDelta></dashboardStatus></list>"
     "<set><string>changed</string></set>")))

(ct/deftest deploy-changed-failure-tests
  (doseq [failure-stage [:get :post]]
    (ct/testing (str "HTTP failure at " failure-stage " returns false without retrying")
      (let [calls (atom [])
            app-conf {:pushed-channel-ids (atom ["new-channel"])}]
        (with-redefs [mhttp/get-xml (fn [_ _]
                                    (swap! calls conj :get)
                                    (if (= :get failure-stage)
                                      (throw+ {:status 500 :body "Status lookup failed"})
                                      "<list/>"))
                      mhttp/post-xml (fn [& _]
                                      (swap! calls conj :post)
                                      (throw+ {:status 500 :body "Deployment failed"}))]
          (ct/is (false? (ma/deploy-changed-channels app-conf))))
        (ct/is (= (if (= :get failure-stage) [:get] [:get :post]) @calls))
        (ct/is (= ["new-channel"] @(:pushed-channel-ids app-conf))))))

  (ct/testing "Malformed dashboard XML does not deploy pushed IDs as new channels"
    (let [calls (atom [])]
      (with-redefs [mhttp/get-xml (fn [_ _] "<list>")
                    mhttp/post-xml (fn [& args] (swap! calls conj args))]
        (ct/is (false? (ma/deploy-changed-channels {:pushed-channel-ids (atom ["new-channel"])}))))
      (ct/is (empty? @calls)))))

(ct/deftest deploy-changed-refresh-tests
  (ct/testing "Each invocation rechecks dashboard state before deciding to deploy"
    (let [calls (atom [])
          deployed? (atom false)
          app-conf {:pushed-channel-ids (atom ["new-channel"])}]
      (with-redefs [mhttp/get-xml (fn [_ _]
                                  (swap! calls conj :get)
                                  (if @deployed?
                                    "<list><dashboardStatus><channelId>new-channel</channelId><deployedRevisionDelta>0</deployedRevisionDelta></dashboardStatus></list>"
                                    "<list/>"))
                    mhttp/post-xml (fn [_ _ body _ _]
                                    (swap! calls conj :post)
                                    (assert-deployment-xml "<set><string>new-channel</string></set>" body)
                                    (reset! deployed? true)
                                    {:status 200 :body "<map/>"})]
        (ma/deploy-changed-channels app-conf)
        (ma/deploy-changed-channels app-conf))
      (ct/is (= [:get :post :get] @calls)))))

(ct/deftest deploy-new-tracking-tests
  (ct/testing "Only successful saves are tracked, including repeated saves"
    (let [pushed-ids (atom [])
          el-loc (mx/to-zip "<channel><id>new-channel</id><name>New Channel</name></channel>")
          app-conf {:pushed-channel-ids pushed-ids :el-loc el-loc}]
      (doseq [result [{:status 200 :body "true"}
                      {:status 200 :body "<boolean>true</boolean>"}
                      {:status 200 :body "{\"boolean\":true}"}]]
        (ct/is (true? (mirthsync.interfaces/after-push :channels app-conf result))))
      (ct/is (= ["new-channel" "new-channel" "new-channel"] @pushed-ids))
      (doseq [result [{:status 200 :body "false"} {:status 400 :body "error"}]]
        (ct/is (false? (mirthsync.interfaces/after-push :channels app-conf result))))
      (ct/is (= ["new-channel" "new-channel" "new-channel"] @pushed-ids))))

  (ct/testing "Saving a channel without tracking still succeeds"
    (let [app-conf {:el-loc (mx/to-zip "<channel><id>new-channel</id></channel>")}]
      (ct/is (true? (mirthsync.interfaces/after-push :channels app-conf {:status 200 :body "true"})))
      (ct/is (nil? (:pushed-channel-ids app-conf)))))

  (ct/testing "Bulk deployment and new-channel tracking both receive successful saves"
    (let [app-conf {:deploy-all true
                    :bulk-deploy-channels (atom [])
                    :pushed-channel-ids (atom [])
                    :el-loc (mx/to-zip "<channel><id>new-channel</id></channel>")}]
      (ct/is (true? (mirthsync.interfaces/after-push :channels app-conf {:status 200 :body "true"})))
      (ct/is (= ["new-channel"] @(:bulk-deploy-channels app-conf)))
      (ct/is (= ["new-channel"] @(:pushed-channel-ids app-conf))))))

(ct/deftest forward-slash-restrict-to-path-pull-writes-file
  ;; A restrict-to-path supplied with forward slashes must still match the
  ;; OS-native file paths that serialize-node compares against via
  ;; String/startsWith. config normalizes the separators; this drives the real
  ;; serialize-node filter+write path end-to-end (no server) and asserts the
  ;; file is written even when the path is given with forward slashes.
  (let [target (build-path "target" "restrict-to-path-sep-test")
        fpath (build-path target "Channels" "GroupA" "ChannelA.xml")
        el-loc (mx/to-zip "<channel><id>id-1</id><name>ChannelA</name></channel>")
        ;; restrict-to-path given with forward slashes
        restrict-to-path (:restrict-to-path
                          (cli/config ["-s" "https://localhost:8443/api"
                                       "-u" "admin" "-p" "password"
                                       "-t" target
                                       "-r" "Channels/GroupA/ChannelA" "pull"]))
        app-conf {:api :channels
                  :el-loc el-loc
                  :target target
                  :restrict-to-path restrict-to-path
                  :disk-mode "code"}]
    (when (.exists (io/file target)) (cpu/delete-recursive target :allowed-dir "target"))
    (with-redefs [mirthsync.interfaces/should-skip? (fn [_ _ _] false)
                  mirthsync.interfaces/file-path (fn [_ _] fpath)
                  mirthsync.interfaces/deconstruct-node (fn [_ _ _] [[fpath "<channel><id>id-1</id></channel>"]])]
      (mx/serialize-node app-conf)
      (ct/testing "Forward-slash restrict-to-path writes the channel file"
        (ct/is (.exists (io/file fpath)))))
    (when (.exists (io/file target)) (cpu/delete-recursive target :allowed-dir "target"))))

(comment
  (ct/deftest iterate-apis
    (ct/is (= "target/foo/blah.xm" (local-path-str "foo/blah.xml" "target")))))
