FROM clojure:openjdk-8-tools-deps-1.10.0.442
RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app
COPY deps.edn /usr/src/app/deps.edn
RUN clj -Sforce
COPY . /usr/src/app
ENTRYPOINT ["clojure", "-m", "tvanhens.clj-kondo-action.main"]
