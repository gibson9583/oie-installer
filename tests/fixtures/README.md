# Windows launcher manifest regression input

`temurin-21.0.12.1-console.manifest` is resource `RT_MANIFEST`, ID 1,
language 1033 from `jpackageapplauncher.exe` in the Windows x64 Temurin
21.0.12.1+1 JDK. The trailing NUL was removed. This is the JDK recorded in
the failed Windows x64 CI run 40857824594 at commit 94715eb.

Source archive:
https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip

SHA-256, checked against the release's published checksum:
`f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e`

Extracted launcher SHA-256:
`3086ff3c21c11ae841b1f1b07d2f2bea8ecd77ce23b5b4b1d26ec063da7242bc`

Its explicit closing `requestedExecutionLevel` tag reproduces the former
regex's failure to replace `asInvoker`. The tests also run the real SDK
round trip against the installed JDK on Windows CI.
