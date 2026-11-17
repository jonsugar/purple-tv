#!/usr/bin/env python3
"""Forward local ADB traffic to a TV through macOS netcat."""

import argparse
import ipaddress
import os
import socket
import socketserver
import subprocess
import threading


def port(value):
    number = int(value)
    if not 1 <= number <= 65535:
        raise argparse.ArgumentTypeError("The port must be between 1 and 65535.")
    return number


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        process = subprocess.Popen(
            ['/usr/bin/nc', '-G', '5', self.server.tv_host, str(self.server.tv_port)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, bufsize=0,
        )

        def upstream():
            try:
                while True:
                    data = self.request.recv(65536)
                    if not data:
                        break
                    remaining = memoryview(data)
                    while remaining:
                        written = os.write(process.stdin.fileno(), remaining)
                        if not written:
                            return
                        remaining = remaining[written:]
            except (OSError, ValueError):
                pass
            finally:
                try:
                    process.stdin.close()
                except OSError:
                    pass

        threading.Thread(target=upstream, daemon=True).start()
        try:
            while True:
                data = os.read(process.stdout.fileno(), 65536)
                if not data:
                    break
                self.request.sendall(data)
        except OSError:
            pass
        finally:
            try:
                self.request.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tv_host', type=ipaddress.ip_address, help='TV IP address')
    parser.add_argument('--tv-port', type=port, default=5555, help='TV ADB port (default: 5555)')
    parser.add_argument('--port', type=port, default=15555, help='Local relay port (default: 15555)')
    args = parser.parse_args()

    try:
        with Server(('127.0.0.1', args.port), Relay) as server:
            server.tv_host = str(args.tv_host)
            server.tv_port = args.tv_port
            print(f'TV relay: 127.0.0.1:{args.port} -> {args.tv_host}:{args.tv_port}', flush=True)
            print('Leave this terminal open during ADB use. Ctrl-C stops the relay.', flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                print('\nRelay stopped.', flush=True)
    except OSError as error:
        parser.exit(1, f'Could not start the relay: {error}\n')


if __name__ == '__main__':
    main()
