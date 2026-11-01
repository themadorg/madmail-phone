"""Device regression: service recovery and bounded background listener checks.

Start/stop/background/lock the app through the device UI before running this.
--mode recover deliberately SIGKILLs only Madmail's process to test sticky recovery.
This never force-stops, launches UI, changes battery settings, or reads account secrets.
"""

import argparse
import socket
import ssl
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument(
        "--mode", choices=("recover", "monitor", "stopped"), required=True
    )
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if not 5 <= args.seconds <= 180:
        parser.error("--seconds must be between 5 and 180")

    def adb(*parts, check=True):
        return subprocess.run(
            [args.adb, "-s", args.serial, *parts],
            check=check,
            capture_output=True,
            text=True,
            timeout=15,
        ).stdout.strip()

    def pid():
        return adb("shell", "pidof", "org.madmail.mobile", check=False)

    def requested():
        xml = adb(
            "shell",
            "run-as",
            "org.madmail.mobile",
            "cat",
            "shared_prefs/hosting-session.xml",
        )
        return (
            ET.fromstring(xml).find("boolean[@name='requested']").get("value") == "true"
        )

    ports = []
    try:
        for remote in (18443, 11993, 11465):
            ports.append(int(adb("forward", "tcp:0", f"tcp:{remote}")))
        certificate = adb(
            "shell",
            "run-as",
            "org.madmail.mobile",
            "cat",
            "files/madmail/data/certs/fullchain.pem",
        )
        tls = ssl.create_default_context(cadata=certificate)
        tls.check_hostname = (
            False  # Pin the owned server certificate, not the USB proxy host.
        )

        def healthy():
            for port, prefix in zip(
                ports, (b"HTTP/1.1 200", b"* OK", b"220"), strict=True
            ):
                with (
                    socket.create_connection(("127.0.0.1", port), timeout=3) as raw,
                    tls.wrap_socket(raw, server_hostname="madmail.local") as stream,
                ):
                    stream.settimeout(3)
                    if port == ports[0]:
                        stream.sendall(
                            b"GET / HTTP/1.1\r\nHost: madmail.local\r\nConnection: close\r\n\r\n"
                        )
                    assert stream.recv(256).startswith(prefix), (
                        "Unexpected listener response"
                    )

        if args.mode == "recover":
            original = pid()
            assert original.isdigit(), (
                "Expected exactly one running Madmail app process"
            )
            assert requested(), "Hosting must be started through the UI first"
            service = adb(
                "shell", "dumpsys", "activity", "services", "org.madmail.mobile"
            )
            assert "isForeground=true" in service and "startCommandResult=1" in service
            healthy()
            # run-as restricts this signal to the app UID. Never kill an arbitrary shell PID.
            adb("shell", "run-as", "org.madmail.mobile", "kill", "-9", original)
            deadline = time.monotonic() + args.seconds
            while time.monotonic() < deadline:
                replacement = pid()
                if replacement and replacement != original:
                    try:
                        healthy()
                    except (OSError, AssertionError):
                        pass
                    else:
                        assert requested()
                        print(
                            "PASS: Android restored hosting after process death; HTTPS, IMAP and SMTP respond."
                        )
                        return
                time.sleep(2)
            raise AssertionError("Hosting did not recover within the test window")

        original = pid()
        deadline = time.monotonic() + args.seconds
        samples = 0
        while time.monotonic() < deadline:
            if args.mode == "monitor":
                assert requested() and pid() == original, "Hosting session changed"
                healthy()
            else:
                assert not requested(), "Explicit stop was not persisted"
                for port in ports:
                    try:
                        with (
                            socket.create_connection(
                                ("127.0.0.1", port), timeout=2
                            ) as raw,
                            tls.wrap_socket(raw, server_hostname="madmail.local"),
                        ):
                            raise AssertionError("Stopped server still accepts TLS")
                    except OSError:
                        pass
            samples += 1
            time.sleep(5)
        print(f"PASS: {args.mode}, {samples} samples over {args.seconds}s.")
    finally:
        for port in ports:
            adb("forward", "--remove", f"tcp:{port}", check=False)


if __name__ == "__main__":
    main()
