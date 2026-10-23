# MyWatchMate backend image. Packages the jar built by Maven, so build that first:
#   .\mvnw.cmd -B package -DskipTests        (deploy/release.sh does both steps)
#   docker build -t mywatchmate-backend .
#   docker run --env-file app.env -p 8081:8081 mywatchmate-backend
# Configuration comes from environment variables (see .env.example); nothing secret is baked in.

# ---- Unpack the jar into layers (dependencies change rarely, so updates upload only the app layer) ----
FROM eclipse-temurin:21-jre AS layers
WORKDIR /build
COPY target/mywatchmate-backend-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Run: JRE only, as a non-root user ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER app
EXPOSE 8081
# Use up to 75% of the container's memory for the Java heap
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=UTC", "org.springframework.boot.loader.launch.JarLauncher"]
