(ns tvanhens.clj-kondo-action.checks
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.string :as string]
            [clj-http.client :as http]))

(s/fdef get-env
  :args (s/cat :name #{"GITHUB_REPOSITORY"
                       "GITHUB_SHA"
                       "GITHUB_WORKSPACE"})
  :ret string?)

(defn- get-env
  [name]
  (System/getenv name))

(defn build-request
  [req]
  (merge req
         {:headers      (merge (:headers req)
                               {:Accept        "application/vnd.github.antiope-preview+json"
                                :Authorization (format "token %s" (System/getenv "GITHUB_TOKEN"))})
          :as           :json
          :content-type :json}))

(defn- GET
  [url req]
  (http/get url (build-request req)))

(defn- POST
  [url req]
  (http/post url (build-request req)))

(defn- repository
  []
  (get-env "GITHUB_REPOSITORY"))

(defn- sha
  []
  (get-env "GITHUB_SHA"))

(def ^:private finding-level->annotation-level
  {:warning "warning"
   :error   "failure"
   :info    "notice"})

(defn- format-annotation
  [finding]
  {:path             (string/replace (:filename finding)
                                     (str (get-env "GITHUB_WORKSPACE") "/")
                                     "")
   :start_line       (:row finding)
   :end_line         (:row finding)
   :start_column     (:col finding)
   :end_column       (:col finding)
   :message          (:message finding)
   :title            (some-> finding (:type) (name))
   :annotation_level (some-> finding (:level) (finding-level->annotation-level))})

(defn- format-output
  [result]
  (let [{:keys [error warning info duration]} (:summary result)]
    {:title       "clj-kondo"
     :summary     (format "Finished in %s ms. There were %s errors %s warnings and %s notices."
                          duration error warning info)
     :annotations (map format-annotation (:findings result))}))

(s/def :github.check-run.annotation/path string?)
(s/def :github.check-run.annotation/title string?)
(s/def :github.check-run.annotation/annotation_level #{"notice" "warning" "failure"})
(s/def :github.check-run.annotation/message string?)
(s/def :github.check-run.annotation/start_line pos-int?)
(s/def :github.check-run.annotation/end_line pos-int?)
(s/def :github.check-run.annotation/start_column pos-int?)
(s/def :github.check-run.annotation/end_column pos-int?)

(s/def :github.check-run/title string?)
(s/def :github.check-run/summary
  (s/and string?
         #(not (.contains % "null"))))
(s/def :github.check-run/annotations
  (s/coll-of (s/keys :req-un [:github.check-run.annotation/path
                              :github.check-run.annotation/title
                              :github.check-run.annotation/message
                              :github.check-run.annotation/annotation_level
                              :github.check-run.annotation/start_line
                              :github.check-run.annotation/end_line
                              :github.check-run.annotation/start_column
                              :github.check-run.annotation/end_column])))

(s/fdef create-run
  :args (s/cat :output (s/keys :req-un [:github.check-run/title
                                        :github.check-run/summary
                                        :github.check-run/annotations]))
  :ret #{{:status 201}})

(defn- create-run
  [output]
  (POST (format "https://api.github.com/repos/%s/check-runs"
                (repository))
        {:form-params {:output     output
                       :name       "clj-kondo"
                       :head_sha   (sha)
                       :conclusion "success"}}))

(s/def ::int?
  (s/with-gen int? #(sgen/choose 0 5)))

(s/def :clj-kondo.result.summary/error ::int?)
(s/def :clj-kondo.result.summary/info ::int?)
(s/def :clj-kondo.result.summary/warning ::int?)
(s/def :clj-kondo.result.summary/duration ::int?)

(s/def :clj-kondo.result/summary
  (s/keys :req-un [:clj-kondo.result.summary/error
                   :clj-kondo.result.summary/info
                   :clj-kondo.result.summary/warning
                   :clj-kondo.result.summary/duration]))

(s/def :clj-kondo.finding/filename string?)
(s/def :clj-kondo.finding/message string?)
(s/def :clj-kondo.finding/level #{:warning :info :error})
(s/def :clj-kondo.finding/type keyword?)
(s/def :clj-kondo.finding/row pos-int?)
(s/def :clj-kondo.finding/col pos-int?)
(s/def :clj-kondo.result/finding
  (s/keys :req-un [:clj-kondo.finding/filename
                   :clj-kondo.finding/message
                   :clj-kondo.finding/level
                   :clj-kondo.finding/type
                   :clj-kondo.finding/row
                   :clj-kondo.finding/col]))
(s/def :clj-kondo.result/findings
  (s/coll-of :clj-kondo.result/finding))
(s/def :clj-kondo/result
  (s/with-gen
    (s/and (s/keys :req-un [:clj-kondo.result/summary
                            :clj-kondo.result/findings])
           #(let [{:keys [error warning info]} (:summary %)]
              (= (+ error warning info)
                 (count (:findings %)))))
    #(sgen/bind (s/gen :clj-kondo.result/summary)
                (fn [{:keys [error info warning] :as summary}]
                  (sgen/hash-map
                    :summary (sgen/return summary)
                    :findings
                    (s/gen
                      (s/coll-of :clj-kondo.result/finding
                                 :count (+ error info warning))))))))

(s/fdef annotate-run
  :args (s/cat :result :clj-kondo/result))

(defn annotate-run
  [result]
  (let [{:keys [status] :as resp} (create-run (format-output result))]
    (when-not (= 201 status)
      (throw (ex-info "Failed to update check run"
                      {:response resp})))))
