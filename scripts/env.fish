# Fish version of scripts/env.sh:   source scripts/env.fish
set -q JAVA_HOME_PLATEN; and set -gx JAVA_HOME $JAVA_HOME_PLATEN; or set -gx JAVA_HOME $HOME/Android/toolchain/jdk
set -q ANDROID_HOME_PLATEN; and set -gx ANDROID_HOME $ANDROID_HOME_PLATEN; or set -gx ANDROID_HOME $HOME/Android/toolchain/sdk
set -gx ANDROID_SDK_ROOT $ANDROID_HOME
set -q GRADLE_USER_HOME_PLATEN; and set -gx GRADLE_USER_HOME $GRADLE_USER_HOME_PLATEN; or set -gx GRADLE_USER_HOME "/mnt/Games_SSD_Bazzite/Code Scrambler Projects/.gradle-home"
fish_add_path $JAVA_HOME/bin $ANDROID_HOME/platform-tools $ANDROID_HOME/emulator $ANDROID_HOME/cmdline-tools/latest/bin
