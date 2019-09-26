(ns tvanhens.clj-kondo-action.main
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.string :as string]
            [clj-kondo.core :as clj-kondo]
            [tvanhens.clj-kondo-action.checks :as checks]))

(defn instrument?
  []
  (= "t" (System/getenv "INSTRUMENT")))

(defn lint-paths
  [args]
  (let [local-paths (some-> args (first) (string/split #","))]
    (mapv #(str (System/getenv "GITHUB_WORKSPACE") "/" %) local-paths)))

(defn -main
  [& args]
  (when (instrument?) (stest/instrument))
  (let [dirs   (lint-paths args)
        result (clj-kondo/run! {:lint dirs})]
    (checks/annotate-run result)
    (System/exit 1)))
