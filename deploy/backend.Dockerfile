# syntax=docker/dockerfile:1
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JAVA_IMAGE=eclipse-temurin:17-jre-jammy

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY netdisk/pom.xml ./pom.xml
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY netdisk/src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && mkdir /out \
    && cp target/netdisk-*.jar /out/netdisk.jar
COPY deploy/Healthcheck.java /health/Healthcheck.java
RUN javac --release 17 -d /health/classes /health/Healthcheck.java

FROM ${JAVA_IMAGE} AS runtime
ARG APP_UID=10001
ARG APP_GID=10001
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && test "${APP_UID}" -gt 0 && test "${APP_GID}" -gt 0 \
    && groupadd --gid "${APP_GID}" netdisk \
    && useradd --uid "${APP_UID}" --gid "${APP_GID}" --no-create-home --home-dir /opt/netdisk --shell /usr/sbin/nologin netdisk \
    && install -d -o "${APP_UID}" -g "${APP_GID}" -m 0750 /opt/netdisk /data/netdisk
WORKDIR /opt/netdisk
COPY --from=build --chown=netdisk:netdisk /out/netdisk.jar ./netdisk.jar
COPY --from=build --chown=netdisk:netdisk /health/classes ./health
ENV NETDISK_PORT=7090 \
    SPRING_PROFILES_ACTIVE=cloud \
    NETDISK_STORAGE=/data/netdisk/ \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.awt.headless=true -Dfile.encoding=UTF-8"
USER ${APP_UID}:${APP_GID}
EXPOSE 7090
VOLUME ["/data/netdisk"]
HEALTHCHECK --interval=30s --timeout=8s --start-period=60s --retries=3 \
    CMD ["java", "-Xms8m", "-Xmx32m", "-cp", "/opt/netdisk/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/netdisk/netdisk.jar"]
