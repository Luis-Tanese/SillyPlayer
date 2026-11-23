import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: control
    property real from: 0
    property real to: 1
    property real value: 0
    property bool indeterminate: false
    property bool showMeta: true

    readonly property real fraction: indeterminate ? -1 : Math.max(0, Math.min(1, (value - from) / Math.max(1e-9, to - from)))
    readonly property int percent: fraction >= 0 ? Math.round(fraction * 100) : 0

    property double startMs: 0
    property real startFraction: 0
    property real lastFraction: -1
    property double smoothedEtaMs: -1
    property string etaText: ""

    spacing: 4

    function formatEta(ms) {
        if (!isFinite(ms) || ms < 0) return "";
        const totalSec = Math.ceil(ms / 1000);
        if (totalSec <= 1) return "< 1s left";
        if (totalSec < 60) return "~" + totalSec + "s left";
        const mins = Math.floor(totalSec / 60);
        const secs = totalSec % 60;
        if (mins < 60) return secs > 0 ? "~" + mins + "m " + secs + "s left" : "~" + mins + "m left";
        const hours = Math.floor(mins / 60);
        const remMins = mins % 60;
        return remMins > 0 ? "~" + hours + "h " + remMins + "m left" : "~" + hours + "h left";
    }

    function resetEstimate() {
        startMs = 0;
        startFraction = 0;
        lastFraction = -1;
        smoothedEtaMs = -1;
        etaText = "";
    }

    function updateEstimate() {
        if (!visible || indeterminate || fraction < 0) {
            resetEstimate();
            return;
        }
        const now = Date.now();
        if (startMs === 0 || fraction < lastFraction - 0.03) {
            startMs = now;
            startFraction = fraction;
            smoothedEtaMs = -1;
            etaText = fraction >= 1 ? "" : "Estimating time left…";
        }
        lastFraction = fraction;
        if (fraction >= 1) {
            etaText = "";
            return;
        }
        const elapsed = now - startMs;
        const delta = fraction - startFraction;
        if (elapsed >= 500 && delta >= 0.005) {
            const raw = elapsed * (1 - fraction) / delta;
            const clamped = Math.max(0, Math.min(raw, 86400000));
            smoothedEtaMs = smoothedEtaMs < 0 ? clamped : (smoothedEtaMs * 0.7 + clamped * 0.3);
            etaText = "Estimated time left: " + formatEta(smoothedEtaMs);
        } else {
            etaText = "Estimating time left…";
        }
    }

    onVisibleChanged: updateEstimate()
    onIndeterminateChanged: updateEstimate()
    onFractionChanged: updateEstimate()

    Timer {
        interval: 1000
        repeat: true
        running: control.visible && !control.indeterminate && control.fraction > 0 && control.fraction < 1
        onTriggered: control.updateEstimate()
    }

    RowLayout {
        Layout.fillWidth: true
        visible: control.showMeta && !control.indeterminate && control.fraction >= 0
        spacing: 8
        Label {
            text: control.etaText
            color: Theme.muted
            font.pixelSize: 11
            elide: Text.ElideRight
            Layout.fillWidth: true
        }
        Label {
            text: control.percent + "%"
            color: Theme.accent
            font.pixelSize: 11
            font.weight: Font.Medium
        }
    }

    ProgressBar {
        id: bar
        Layout.fillWidth: true
        implicitHeight: 5
        padding: 0
        from: control.from
        to: control.to
        value: control.value
        indeterminate: control.indeterminate
        background: Rectangle {
            implicitHeight: 5
            radius: 2.5
            color: Theme.rail
        }
        contentItem: Item {
            clip: true
            Rectangle {
                id: fill
                width: bar.indeterminate ? parent.width * 0.22 : parent.width * bar.position
                height: parent.height
                radius: 2.5
                color: Theme.accent
                SequentialAnimation on x {
                    running: bar.indeterminate && control.visible
                    loops: Animation.Infinite
                    NumberAnimation {
                        from: -bar.width * 0.22
                        to: bar.width
                        duration: 1300
                        easing.type: Easing.InOutSine
                    }
                }
                Binding {
                    target: fill
                    property: "x"
                    value: 0
                    when: !bar.indeterminate
                    restoreMode: Binding.RestoreNone
                }
            }
        }
    }
}
