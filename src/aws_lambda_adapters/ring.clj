(ns aws-lambda-adapters.ring
  "Ring request/response specification:
  https://github.com/ring-clojure/ring/blob/1.7.0/SPEC"
  ;; https://docs.aws.amazon.com/lambda/latest/dg/with-on-demand-https.html
  (:require [aws-lambda-adapters.core :refer [realize-body sanitize-headers str->int]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.io InputStream File]
           [clojure.lang ISeq]))

(defn resource-path-params->uri
  "Constructs an URI from a resource (string) and a map containing path parameters.

  Input:
  \"/example/resource/{id}/{type}\"
  {\"id\" \"1234\"
   \"type\" \"foo\"}

  Output:
  \"/example/resource/1234/foo\"
  "
  [resource path-params]
  (str/replace resource #"\{[a-z]+\}" (update-keys path-params #(str "{" % "}"))))

(defn api-gw-proxy-event->request
  "Transform AWS API Gateway event to Ring a request."
  [event ctx]
  (let [headers (sanitize-headers (get event "headers"))
        req-ctx (get event "requestContext")]
    {:server-port    (str->int (get headers "x-forwarded-port"))
     :server-name    (get req-ctx "domainName" "")
     :remote-addr    (get-in req-ctx ["identity" "sourceIp"] "")
     :uri            (resource-path-params->uri (get event "resource" "/") (get event "pathParameters"))
     :body           (-> (or (get event "body") "")
                         (.getBytes)
                         io/input-stream)
     ;; API gateway already parsed query params into a map causing
     ;; ring-middelware/wrap-params to be irrelevant.
     :query-params   (get event "queryStringParameters" {})
     :scheme         (keyword (get headers "x-forwarded-proto" "http"))
     :request-method (->
                      (get event "httpMethod" "GET")
                      str/lower-case
                      keyword)
     :protocol       (get req-ctx :protocol "HTTP/1.1")
     :headers        headers

     ;; Extra AWS (non Ring) attributes
     :event          event
     ;; http://docs.aws.amazon.com/lambda/latest/dg/java-context-object.html
     :context        ctx}))

(defn response->api-gw-proxy-response
  "Takes a Ring response and transform it into response that AWS Gateway
  understands when using \"Proxy Resource\". Failing to do so results in
  \"502 - Internal server error\"."
  [response]
  {:statusCode (:status response)
   :headers (:headers response)
   :body (realize-body (:body response))})

(defn api-gw->handler
  "Wraps a Ring handler so it matches the signature of \"deflambada\" for AWS
  Gateway/Lambda integration (see uswitch/lambada)."
  [handler]
  (fn [in out ctx]
    (with-open [w (io/writer out)]
      (->
       (json/read (io/reader in))
       (api-gw-proxy-event->request ctx)
       handler
       response->api-gw-proxy-response
       (json/write w)))))
