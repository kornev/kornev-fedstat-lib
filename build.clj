(ns build
  (:require
   [clojure.string :as str]
   [clojure.tools.build.api :as b]))

(defn- write-pom [{:as conf
                   :keys [basis class-dir lib version src-clojure-dirs]}]
  (printf "Writing pom.xml...%n")
  (printf "  version=%s%n" version)
  (printf "  groupId=%s%n" (namespace lib))
  (printf "  artifactId=%s%n" (name lib))
  (flush)
  (b/write-pom {:basis basis
                :class-dir class-dir
                :lib lib
                :version version
                :src-dirs src-clojure-dirs})
  conf)

(defn- copy-dir [{:as conf
                  :keys [src-clojure-dirs res-dirs class-dir]}]
  (printf "Copying %s...%n" (str/join ", " (concat src-clojure-dirs res-dirs)))
  (flush)
  (b/copy-dir {:src-dirs (concat src-clojure-dirs res-dirs)
               :target-dir class-dir})
  conf)

(defn- jar [{:as conf
             :keys [jar-file class-dir]}]
  (printf "Building %s...%n" jar-file)
  (flush)
  (b/jar {:jar-file jar-file
          :class-dir class-dir})
  conf)

(defn clean "Remove the out folder." [_]
  (printf "Cleaning out...%n")
  (flush)
  (b/delete {:path "out"})
  (b/delete {:path "classes"}))

(defn jar-lib "Build the library JAR file." [_]
  (let [lib     'io.github.kornev/fedstat
        version (format "0.%s" (b/git-count-revs nil))]
    (-> {:src-clojure-dirs ["src"]
         :res-dirs []
         :class-dir "out/classes"
         :lib lib
         :version version
         :basis (b/create-basis {:project "deps.edn"})
         :jar-file (format "out/%s-%s.jar" (name lib) version)}
        write-pom
        copy-dir
        jar)))
