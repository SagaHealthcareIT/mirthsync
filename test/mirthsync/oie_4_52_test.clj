(ns mirthsync.oie-4-52-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once oie-4-52-fixture)

(def version "oie-4-52")
(def baseline-dir (build-path "target" "test-data" (str "oie-" version "-baseline")))
(def repo-dir (build-path "target" (str "tmp-" version)))

(deftest integration-with-oie-4-52
  (test-integration repo-dir baseline-dir version))