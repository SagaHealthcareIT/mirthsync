(ns mirthsync.apis-test
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zip-xml]
            [clojure.test :refer :all]
            [mirthsync.apis :refer :all]
            [mirthsync.xml]
            [mirthsync.xml :as mxml]
            [clojure.zip :as zip]))

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
                             (zip/next channel-groups)
                             (nth apis 6)))))
  (testing "Nested code template path is valid"
    (is (= "target/CodeTemplates/Library 1.xml"
           (nested-file-path code-template-libraries
                             [:list :codeTemplateLibrary :codeTemplate]
                             "target"
                             (zip/next code-template-libraries)
                             (nth apis 4))))))

;; (channel-file-path {:server-groups channel-groups :el-loc (zip/next channel-groups)
;;                     :target "target"
;;                     :api (nth apis 6)})
;; (codetemplate-file-path {:server-codelibs code-template-libraries :el-loc (zip/next code-template-libraries)
;;                     :target "target"
;;                     :api (nth apis 4)})


;; (deftest iterate-apis
;;   (is (= "target/foo/blah.xm" (local-path-str "foo/blah.xml" "target"))))
