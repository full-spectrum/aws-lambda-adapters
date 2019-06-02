(ns aws-lambda-adapters.core
  (:require [clojure.string :as str])
  (:import [java.io InputStream File]
           [clojure.lang ISeq]))

(defprotocol ResponseBody
  (wrap-body [body]))

(extend-protocol ResponseBody
  InputStream
  (wrap-body [body] (slurp body))
  File
  (wrap-body [body] (slurp body))
  ISeq
  (wrap-body [body] (str/join "\n" (map wrap-body body)))
  nil
  (wrap-body [body] body)
  Object
  (wrap-body [body] body))

(defn realize-body
  "Convinience function to make the ResponseBody protocol useable outside the
  namespace."
  [body]
  (wrap-body body))

(defn str->int
  [s]
  (try
    (Integer/parseInt s)
    (catch NumberFormatException _)))

(defn sanitize-headers
  [headers]
  (into {} (map (fn -header-keys [[k v]]
                  [(str/lower-case (name k)) v])
                headers)))
