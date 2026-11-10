set -e
cd /workspace
mkdir -p .toolchain
cd .toolchain

echo "=== downloading cmdline-tools ==="
curl -sSL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -o cmdline-tools.zip -d cmdline-tools-tmp
mkdir -p /workspace/.toolchain/android-sdk/cmdline-tools
mv cmdline-tools-tmp/cmdline-tools /workspace/.toolchain/android-sdk/cmdline-tools/latest
rm -rf cmdline-tools.zip cmdline-tools-tmp

export ANDROID_HOME=/workspace/.toolchain/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

echo "=== accepting licenses ==="
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "=== installing platform + build-tools ==="
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | tail -5

echo "=== downloading gradle ==="
curl -sSL -o gradle.zip https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
unzip -q -o gradle.zip -d /workspace/.toolchain
rm -f gradle.zip

echo "=== versions ==="
java -version 2>&1 | head -1
/workspace/.toolchain/gradle-8.11.1/bin/gradle --version 2>&1 | grep -E "Gradle|JVM" | head -3
ls $ANDROID_HOME/platforms $ANDROID_HOME/build-tools
echo "=== TOOLCHAIN READY ==="
