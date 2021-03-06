#!/usr/bin/env python
# -*- coding: utf-8 -*-
#
# Copyright (c) 2010 The Regents of the University of California
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
#   * Redistributions of source code must retain the above copyright notice,
#     this list of conditions and the following disclaimer.
#   * Redistributions in binary form must reproduce the above copyright notice,
#     this list of conditions and the following disclaimer in the documentation
#     and/or other materials provided with the distribution.
#   * Neither the name of the author nor the names of any contributors may be
#     used to endorse or promote products derived from this software without
#     specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
# OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

"""A script for testing basic Dapper functionality.
"""

__author__ = "Roy Liu <carsomyr@gmail.com>"

import subprocess
import sys
import time
from subprocess import Popen

# Create facades to ensure compatibility with major versions 2 and 3.
if sys.version_info[0] == 3:
    stdin = getattr(sys.stdin, "buffer")
    stdout = getattr(sys.stdout, "buffer")
else:
    stdin = sys.stdin
    stdout = sys.stdout

def main():
    """The main method body.
    """

    subprocess.call(["make", "--", "jars"])

    java_cmd = ["java", "-ea", "-Xmx128M", "-cp", "dapper.jar"]

    processes = []
    processes.append(Popen(java_cmd + ["org.dapper.ui.FlowManagerDriver", "--port", "12121",
                                       "--archive", "dapper-ex.jar", "ex.SimpleTest"]))

    time.sleep(2)

    nclients = 4

    for i in range(nclients):
        processes.append(Popen(java_cmd + ["org.dapper.client.ClientDriver", "--host", "127.0.0.1:12121"]))

    stdout.write(b"\nPress ENTER to exit this test.\n")

    ch = None

    while ch != b"\n" and ch != b"":
        ch = stdin.read(1)

    for i in range(nclients, -1, -1):
        processes[i].kill()
        processes[i].wait()

#

if __name__ == "__main__":
    sys.exit(main())
