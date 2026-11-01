#include <sys/prctl.h>
#include <signal.h>
#include <unistd.h>
#include <stdio.h>
int main(int argc, char **argv) {
    if (argc < 2) return 64;
    pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0) return 70;
    if (getppid() != parent) return 70;
    execv(argv[1], &argv[1]);
    perror("engine launch");
    return 71;
}
