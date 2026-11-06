FROM openjdk:11-slim-bullseye
RUN apt-get update && apt-get install curl -y
RUN curl -L -O https://github.com/SagaHealthcareIT/mirthsync/releases/download/3.1.0/mirthsync-3.1.0.tar.gz\
  && echo 'e602af6636cf139146377f98cf56331d7cb1f62fa0cdf57e8555a4a66388d420 mirthsync-3.1.0.tar.gz' | sha256sum -c

RUN tar -xvzf mirthsync-3.1.0.tar.gz -C /opt\
  && ln -s /opt/mirthsync-3.1.0/bin/mirthsync.sh /usr/local/bin/mirthsync

RUN mkdir /mirth_configs
