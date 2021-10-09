(ns mirthsync.apis-test
  (:require [clojure.data.zip.xml :refer [text text= xml-> xml1->]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [clojure.zip :refer [node append-child xml-zip up children]]
            [mirthsync.actions :as actions]
            [mirthsync.apis :refer :all]
            [clojure.data.xml :refer [indent-str parse-str]]
            [mirthsync.files :as files]
            mirthsync.xml))

(def channel-groups (mirthsync.xml/to-zip "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<set>
  <channelGroup version=\"3.11.0\">
    <id>630d82bd-0727-48d6-bf13-bfa86080d9f5</id>
    <name>/this\\ is /a \\ group/\\with weird\\characters/</name>
    <revision>1</revision>
    <lastModified>
      <time>1633619775310</time>
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
      <time>1633619775481</time>
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

(def code-template-libraries (mirthsync.xml/to-zip "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<list>
  <codeTemplateLibrary version=\"3.11.0\">
    <id>3e5488e0-2c95-456e-bce3-0178d365d198</id>
    <name>Library 1</name>
    <revision>1</revision>
    <lastModified>
      <time>1633625072821</time>
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
      <time>1633625072951</time>
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


(deftest test-nested-file-path
  (testing "Nested channel path is valid"
    (is (= "target/Channels/%2Fthis%5C is %2Fa %5C group%2F%5Cwith weird%5Ccharacters%2F.xml"
           (nested-file-path channel-groups
                             [:set :channelGroup :channel]
                             "target"
                             (clojure.zip/next channel-groups)
                             (nth apis 6)))))
  (testing "Nested code template path is valid"
    (is (= "target/CodeTemplates/Library 1.xml"
           (nested-file-path code-template-libraries
                             [:list :codeTemplateLibrary :codeTemplate]
                             "target"
                             (clojure.zip/next code-template-libraries)
                             (nth apis 4))))))

;; (xml1-> channel-groups
;;         clojure.zip/down
;;         ;; children
;;       ;;   :id
;;       ;;   (text= ((:find-id (nth apis 6)) (mirthsync.xml/to-zip "<channel version=\"3.11.0\">
;;       ;;   <id>2521ed7e-156d-47dd-b701-0705583b99ec</id>
;;       ;;   <revision>0</revision>
;;       ;; </channel>")))
;;         up)

(comment
  (xml-> channel-groups children :id node)

  (channel-file-path {:server-groups channel-groups :el-loc (next channel-groups)
                      :target "target"
                      :api (nth apis 6)})
  (codetemplate-file-path {:server-codelibs code-template-libraries :el-loc (next code-template-libraries)
                           :target "target"
                           :api (nth apis 4)})

  (deftest iterate-apis
    (is (= "target/foo/blah.xm" (local-path-str "foo/blah.xml" "target")))))
