# Fish version of scripts/env.sh:   source scripts/env.fish
# Machine-specific paths go in scripts/env.local.fish (git-ignored), sourced first. Example:
#
#   set -gx JAVA_HOME /usr/lib/jvm/java-21-openjdk
#   set -gx ANDROID_HOME $HOME/Android/Sdk
#   set -gx GRADLE_USER_HOME /big/disk/gradle-home     # optional
set -l here (dirname (status --current-filename))
test -f $here/env.local.fish; and source $here/env.local.fish

test -n "$ANDROID_HOME"; and set -gx ANDROID_SDK_ROOT $ANDROID_HOME
test -n "$JAVA_HOME"; and fish_add_path $JAVA_HOME/bin
test -n "$ANDROID_HOME"; and fish_add_path $ANDROID_HOME/platform-tools $ANDROID_HOME/emulator $ANDROID_HOME/cmdline-tools/latest/bin
test -n "$JAVA_HOME"; or echo "platen: JAVA_HOME is not set (JDK 17-24, 21 recommended); see docs/DEVELOPMENT.md" >&2
test -n "$ANDROID_HOME"; or echo "platen: ANDROID_HOME is not set; see docs/DEVELOPMENT.md" >&2
