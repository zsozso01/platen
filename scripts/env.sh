# Source this file to get a working Platen build environment:   source scripts/env.sh
#
# It only sets environment variables; it installs nothing. Override any of them before sourcing
# if your toolchain lives elsewhere. See docs/DEVELOPMENT.md.

# JDK 17-24 is required (Gradle 9 + AGP 9). JDK 21 is the tested one. JDK 25+ may be too new.
export JAVA_HOME="${JAVA_HOME_PLATEN:-$HOME/Android/toolchain/jdk}"
export ANDROID_HOME="${ANDROID_HOME_PLATEN:-$HOME/Android/toolchain/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Keep the multi-GB Gradle cache off a nearly-full home disk. Delete the directory to reclaim space.
export GRADLE_USER_HOME="${GRADLE_USER_HOME_PLATEN:-/mnt/Games_SSD_Bazzite/Code Scrambler Projects/.gradle-home}"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
