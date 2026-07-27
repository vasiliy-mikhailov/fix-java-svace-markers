FROM bjv-alljdk:latest

# Maven resolves everything through the Nexus mirror on the mvn-cache network
COPY settings.xml /root/.m2/settings.xml
COPY settings.xml /opt/apache-maven-3.9.9/conf/settings.xml

# Code navigation (JavaParser SymbolSolver): jars are pre-fetched into nav/lib (docker-build has no
# Nexus network), then compiled offline with javac.
COPY nav/lib /opt/nav/lib
COPY NavServer.java /opt/nav/NavServer.java
RUN /opt/jdk/21/bin/javac -cp "/opt/nav/lib/*" -d /opt/nav/classes /opt/nav/NavServer.java

COPY runner.py /runner.py

ENV PORT=8090
WORKDIR /
ENTRYPOINT []
CMD ["python3", "/runner.py"]
