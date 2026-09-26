#!/usr/bin/env bash
#
# On-device Hindi STT validation for speech-engine (Tanmay's module).
#
# Runs the existing instrumented tests that exercise the milestone
#   known Hindi WAV -> ONNX Runtime Android -> model inference -> CTC decode -> Hindi text
# plus the FP32 / INT8 / INT8-MatMul-only load-and-timing checks, on one
# connected physical device, and saves everything needed to cite the result
# later into a timestamped output directory (not build/, which is gitignored).
#
# Nothing here is bundled into the APK: the ONNX models (~800 MB for all three)
# and fixtures are pushed with adb into the test app's external files dir,
# which is where the tests read them from.
#
# Usage:
#   speech-engine/scripts/run_stt_device_validation.sh [output_dir]
#
# Environment:
#   ANDROID_SERIAL            pick a device when more than one is attached
#   ITANTRA_STT_EXPORT_DIR    default: ~/itantra-stt-validation/export
#   SKIP_INT8=1               do not push/run the INT8 variants (FP32 only)
#   TEST_FILTER=<regex>       run only the test entries matching this regex
#
# Each test method runs in its own `am instrument` invocation, so an OOM kill
# or native crash in one model does not hide the results of the others.
#
# Privacy: a full `logcat -d` contains every app on the device. Only the test
# process's own lines are written to *.logcat.txt (meant to be committed);
# the full dump goes to *.logcat-full.txt, which is gitignored. Low-memory
# killer activity is reported in summary.txt as a count, without the names of
# the apps it killed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXPORT_DIR="${ITANTRA_STT_EXPORT_DIR:-$HOME/itantra-stt-validation/export}"
PKG="com.itantra.speechengine.test"
RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"
DEVICE_DIR="/sdcard/Android/data/$PKG/files"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${1:-$REPO_ROOT/speech-engine/validation-results/device/$STAMP}"

fail() { echo "ERROR: $*" >&2; exit 1; }

command -v adb >/dev/null || fail "adb not found on PATH"

# --- 1. Exactly one target device ------------------------------------------
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    mapfile -t SERIALS < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    [[ ${#SERIALS[@]} -eq 1 ]] || fail "expected exactly 1 device, found ${#SERIALS[@]} (set ANDROID_SERIAL)"
    export ANDROID_SERIAL="${SERIALS[0]}"
fi
mkdir -p "$OUT_DIR"
echo "Device: $ANDROID_SERIAL  Output: $OUT_DIR"

# --- 2. Record device facts (the target's CPU/RAM decide feasibility) --------
{
    echo "serial=$ANDROID_SERIAL"
    echo "run_utc=$STAMP"
    for p in ro.product.manufacturer ro.product.model ro.build.version.release \
             ro.build.version.sdk ro.product.cpu.abi ro.board.platform ro.hardware; do
        echo "$p=$(adb shell getprop "$p" | tr -d '\r')"
    done
    echo "--- /proc/cpuinfo (Features, CPU part, unique) ---"
    adb shell cat /proc/cpuinfo | tr -d '\r' | grep -E "^(Features|CPU part|Hardware)" | sort -u
    echo "--- /proc/meminfo ---"
    adb shell cat /proc/meminfo | tr -d '\r' | grep -E "^(MemTotal|MemAvailable|SwapTotal)"
    echo "--- storage ---"
    adb shell df -h /sdcard | tr -d '\r'
} > "$OUT_DIR/device-info.txt"
cat "$OUT_DIR/device-info.txt"

ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$ABI" == "arm64-v8a" ]] || fail "device ABI is $ABI, this validation targets arm64-v8a"

# --- 3. Build + install the instrumented-test APK (no uninstall afterwards,
#        unlike connectedAndroidTest, so pushed models survive between runs) --
(cd "$REPO_ROOT" && ./gradlew :speech-engine:installDebugAndroidTest --console=plain) \
    > "$OUT_DIR/gradle-install.log" 2>&1 || fail "installDebugAndroidTest failed, see $OUT_DIR/gradle-install.log"

# --- 4. Push models and fixtures (skipped when an identical-size copy exists) -
FILES=(
    "$EXPORT_DIR/indicconformer_hi.onnx"
    "$EXPORT_DIR/tokens.txt"
    "$EXPORT_DIR/android_fixtures/real_hindi_features_1x80x1819.f32"
    "$EXPORT_DIR/android_fixtures/reference.txt"
)
if [[ "${SKIP_INT8:-0}" != "1" ]]; then
    FILES+=("$EXPORT_DIR/indicconformer_hi_int8.onnx" "$EXPORT_DIR/indicconformer_hi_int8_matmul.onnx")
fi
adb shell mkdir -p "$DEVICE_DIR"
for f in "${FILES[@]}"; do
    [[ -f "$f" ]] || fail "missing local file $f"
    name="$(basename "$f")"
    local_size="$(stat -c %s "$f")"
    remote_size="$(adb shell stat -c %s "$DEVICE_DIR/$name" 2>/dev/null | tr -d '\r')"
    if [[ "$local_size" == "$remote_size" ]]; then
        echo "push: $name already on device ($local_size bytes)"
    else
        echo "push: $name ($local_size bytes)"
        adb push "$f" "$DEVICE_DIR/$name" >/dev/null || fail "adb push $name failed"
    fi
    echo "$name $local_size $(sha256sum "$f" | cut -d' ' -f1)" >> "$OUT_DIR/pushed-files.txt"
done

# --- 5. Run tests, milestone first -------------------------------------------
# Entry format: <class>#<method>[|<modelFile>]. A modelFile is passed to the
# test as `-e modelFile`; IndicConformerRecognizerInstrumentedTest defaults
# to the FP32 export when none is given.
R="com.itantra.speechengine.stt.IndicConformerRecognizerInstrumentedTest"
V="com.itantra.speechengine.stt.IndicConformerOnnxDeviceValidationTest"
TESTS=(
    "$R#knownPcmClip_recognizesCorrectHindiText"
    "$V#fp32_realHindiFeatures_ctcGreedyDecode_onDevice"
    "$V#fp32_loadsAndRunsSyntheticTensorOnDevice"
    "$R#knownPcmClip_repeatedInferenceIsStable"
)
if [[ "${SKIP_INT8:-0}" != "1" ]]; then
    TESTS+=(
        "$V#int8_loadsAndRunsSyntheticTensorOnDevice"
        "$V#int8Matmul_loadsAndRunsSyntheticTensorOnDevice"
        "$R#knownPcmClip_recognizesCorrectHindiText|indicconformer_hi_int8_matmul.onnx"
        "$R#knownPcmClip_repeatedInferenceIsStable|indicconformer_hi_int8_matmul.onnx"
    )
fi
if [[ -n "${TEST_FILTER:-}" ]]; then
    mapfile -t TESTS < <(printf '%s\n' "${TESTS[@]}" | grep -E -- "$TEST_FILTER")
    [[ ${#TESTS[@]} -gt 0 ]] || fail "TEST_FILTER '$TEST_FILTER' matched no tests"
fi

: > "$OUT_DIR/summary.txt"
for entry in "${TESTS[@]}"; do
    t="${entry%%|*}"
    model=""
    [[ "$entry" == *"|"* ]] && model="${entry#*|}"
    extra=()
    [[ -n "$model" ]] && extra=(-e modelFile "$model")

    short="${t##*.}"
    log="$OUT_DIR/${short//#/__}${model:+__${model%.onnx}}"
    echo "=== $t${model:+ [modelFile=$model]}"
    adb logcat -c
    adb shell am instrument -w -r "${extra[@]}" -e class "$t" "$RUNNER" | tr -d '\r' > "$log.instrument.txt"
    adb logcat -d -v time | tr -d '\r' > "$log.logcat-full.txt"

    # Keep only the test process's own lines (identified via the TestRunner tag).
    pid="$(grep -m1 -oE "TestRunner\( *[0-9]+\)" "$log.logcat-full.txt" | grep -oE "[0-9]+")"
    if [[ -n "$pid" ]]; then
        grep -E "\( *$pid\): " "$log.logcat-full.txt" > "$log.logcat.txt"
    else
        : > "$log.logcat.txt"
    fi
    lmk_kills="$(grep -c "lowmemorykiller.*Kill '" "$log.logcat-full.txt")"
    own_kill="$(grep -cE "lowmemorykiller.*Kill '$PKG'|Process $PKG .*has died" "$log.logcat-full.txt")"

    if grep -q "^OK (1 test)" "$log.instrument.txt"; then status="PASS"
    elif grep -q "FAILURES!!!" "$log.instrument.txt"; then status="FAIL"
    else status="NO_RESULT (process crash/kill? see $log.logcat-full.txt)"; fi

    {
        echo "[$status] $t${model:+ [modelFile=$model]}"
        grep "REAL ANDROID MEASUREMENT" "$log.logcat.txt" | sed 's/^.*REAL ANDROID MEASUREMENT/  REAL ANDROID MEASUREMENT/'
        echo "  lowmemorykiller kills of other apps during this test: $lmk_kills; test process killed/died: $own_kill"
        grep -E "Could not find an implementation|\bORT_[A-Z_]+|OrtException|OutOfMemoryError|FATAL EXCEPTION|SIGSEGV" \
            "$log.logcat.txt" | head -10 | sed 's/^/  ! /'
        grep -A3 "^There was 1 failure" "$log.instrument.txt" | sed 's/^/  ! /'
    } | tee -a "$OUT_DIR/summary.txt"
done

echo
echo "Summary: $OUT_DIR/summary.txt"
