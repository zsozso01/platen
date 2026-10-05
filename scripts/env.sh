# Source this file to get a working Platen build environment:   source scripts/env.sh
#
# It only sets environment variables; it installs nothing. Machine-specific paths belong in
# scripts/env.local.sh (git-ignored), which is sourced first. Example:
#
#   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
#   export ANDROID_HOME=$HOME/Android/Sdk
#   export GRADLE_USER_HOME=/big/disk/gradle-home     # optional: keep the multi-GB cache elsewhere
#
# See docs/DEVELOPMENT.md.

_platen_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
[ -f "$_platen_dir/env.local.sh" ] && . "$_platen_dir/env.local.sh"
unset _platen_dir

[ -n "$ANDROID_HOME" ] && export ANDROID_SDK_ROOT="$ANDROID_HOME"
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -n "$ANDROID_HOME" ] && export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

[ -n "$JAVA_HOME" ] || echo "platen: JAVA_HOME is not set (JDK 17-24, 21 recommended); see docs/DEVELOPMENT.md" >&2
[ -n "$ANDROID_HOME" ] || echo "platen: ANDROID_HOME is not set; see docs/DEVELOPMENT.md" >&2
