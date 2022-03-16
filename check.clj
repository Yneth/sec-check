#!/usr/bin/env bb
(require '[babashka.process :refer [process]]
         '[babashka.curl :as curl])


(def home-dir     (System/getenv "HOME"))
(def data-dir     (str home-dir "/.sec"))

(def tg-chat-id   (System/getenv "TG_CHAT_ID"))
(def tg-bot-token (System/getenv "TG_BOT_TOKEN"))

(def telegram?    (and tg-chat-id tg-bot-token))

(declare run-which-cmd)
(def lsof-path    (delay (run-which-cmd "lsof")))
(def diff-path    (delay (run-which-cmd "diff")))


(defn notify-telegram [target-name data]
  (let [max-lines     50
        prefixed-data (str "#security \n" (str/upper-case target-name) "\n" data)]
    (->> (str/split-lines prefixed-data)
         (partition-all max-lines)
         (map (partial str/join \newline))
         (run!
           (fn [message-string]
             (curl/post
               (format "https://api.telegram.org/bot%s/sendMessage" tg-bot-token)
               {:headers {"Content-Type" "application/json"}
                :body    (json/encode {:chat_id tg-chat-id 
                                       :text message-string
                                       :disable_notification true})}))))))

(defn run-which-cmd [cmd-lookup]
  (let [resp (shell/sh "which" cmd-lookup)]
    (when (pos? (:exit resp))
      (let [err (or (:err resp) (:out resp))]
        (throw (ex-info "Failed to find cmd path" {:cmd cmd-lookup :err err}))))
    (str/trim (:out resp))))

(defn run-diff-cmd [p0 p1]
  (-> @(process [@diff-path "--color" p0 p1] {:out :string :err :inherit}) :out))

(defmacro ->obj [& symbols]
  (reduce
    (fn [acc sym]
      (assoc acc (keyword sym) sym))
    {}
    symbols))

(defn run-lsof-cmd []
  (as-> (:out (shell/sh @lsof-path "-i" "+c" "32")) $
    (str/split-lines $)
    (drop 1 $)
    (map #(str/split % #"\s+") $)
    (map
      (fn [[cmd pid user fd type device size node name status :as row]]
        (->obj cmd pid user fd type device size node name status)) $)
    (map
      (fn [obj]
        (-> obj
            (update :status #(when % (str/replace % #"[\(\)]" "")))
            (update :cmd #(str/replace % #"\\x20" " ")))) $)))

(defn network-info []
  (->> (run-lsof-cmd)
       (map #(select-keys % #{:cmd :node :name :status}))
       (distinct)
       (sort-by (juxt :cmd :name))))

(defn file-diff [target-name {:keys [cmd to]} {:keys [data-dir]}]
  (let [curr-data (cond
                    (fn? cmd) (cmd)
                    (coll? cmd) (:out (apply shell/sh cmd)))

        tmp-f     (str data-dir "/sec.tmp")
        curr-f    (str data-dir to)]
    (spit tmp-f curr-data)

    (println (format "Checking %s" (name target-name)))

    (when (.exists (io/file curr-f))
      (when-let [res (not-empty (run-diff-cmd curr-f tmp-f))]
        (println res)
        (when (not telegram?)
          (notify-telegram target-name res))))

    (spit curr-f curr-data)))

(defn obj-file-diff [target-name {:keys [cmd to]} {:keys [data-dir]}]
  #_TODO)

(def targets
  {:root-agents
   {:cmd ["ls" "-lah" "/Library/LaunchAgents/"]
    :to  "/root-agents"
    :run file-diff}
   :root-daemons
   {:cmd ["ls" "-lah" "/Library/LaunchDaemons/"]
    :to  "/root-daemons"
    :run file-diff}

   :user-agents
   {:cmd ["ls" "-lah" (str home-dir "/Library/LaunchAgents/")]
    :to  "/user-agents"
    :run file-diff}

   :brew-list
   {:cmd ["brew" "list"]
    :to  "/brew-list"
    :run file-diff}

   :app-list
   {:cmd ["ls" "-lah" "/Applications"]
    :to  "/app-list"
    :run file-diff}

   :pip
   {:cmd ["pip" "list"]
    :to  "/pip"
    :run file-diff}

   :pip2
   {:cmd ["pip2" "list"]
    :to  "/pip2"
    :run file-diff}

   :pip3
   {:cmd ["pip3" "list"]
    :to  "/pip3"
    :run file-diff}

   :user-app-support
   {:cmd ["ls" (str home-dir "/Library/Application Support/")]
    :to  "/user-app-support"
    :run file-diff}

   :root-app-support
   {:cmd ["ls" "/Library/Application Support/"]
    :to  "/root-app-support"
    :run file-diff}

   :network
   {:cmd network-info
    :to  "/network-info"
    :run obj-file-diff}

   ;:defaults
   ;{:cmd ["defaults" "read"]
   ; :to  "/defaults"
   ; :run file-diff}
   })

(defn run-check [targets]
  (let [opts {:data-dir data-dir :home-dir home-dir}]
    (doseq [[target-name target] targets
            :let [run-fn (:run target)]]
      (run-fn target-name target opts))))

(defn ensure-dir [^String dir]
  (-> dir
      (java.nio.file.Paths/get (into-array String []))
      (java.nio.file.Files/createDirectories (into-array java.nio.file.attribute.FileAttribute []))))

(ensure-dir data-dir)
(when (not telegram?)
  (println "telegram env variables are not set, will skip notification"))

(run-check targets)
(println "Done!")
