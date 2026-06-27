# W5d k8s bench image (class-E k8s-native events).
#
# A plain JRE base image rather than Spring Boot's bootBuildImage: the graalvm native
# plugin on this project forces bootBuildImage into a GraalVM native-image build, which
# fails on Spring Boot 4 and is unnecessary for the chaos Deployment. A stock JRE also
# leaves heap sizing to the JVM's container defaults (no Paketo memory calculator), so the
# JVM boots well within the pod limit while the oomKill fault's native off-heap allocation
# grows the resident set past the cgroup boundary -> a genuine kernel SIGKILL.
#
# The build context must be the directory that holds the boot jar (it contains exactly one
# spring-petclinic-*-SNAPSHOT.jar), e.g.:
#   ./gradlew bootJar
#   docker build -t spring-petclinic:w5d -f k8s/Dockerfile <build>/libs
# The image lands in the shared Docker Desktop image store, so the Deployment's
# imagePullPolicy: Never resolves it with no registry push.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY spring-petclinic-*-SNAPSHOT.jar /app/app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
