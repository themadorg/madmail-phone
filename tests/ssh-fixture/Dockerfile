FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server sudo ca-certificates iproute2 && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /run/sshd && useradd -m -s /bin/bash fixture && echo 'fixture:test-only-madmail-fixture' | chpasswd && echo 'fixture ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/fixture
COPY systemctl /usr/local/bin/systemctl
RUN chmod 755 /usr/local/bin/systemctl
CMD ["/usr/sbin/sshd", "-D", "-e"]
