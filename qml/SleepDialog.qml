import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

AppDialog {
    id: dialog
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(parent.width - 40, 430)
    title: "Sleep timer"
    standardButtons: Dialog.Close
    ColumnLayout {
        width: parent.width
        spacing: 14
        Label {
            text: app.sleepTimer.active ? "Remaining: " + app.time(app.sleepTimer.remainingSeconds) : "Pause playback after…"
            color: Theme.text
        }
        RowLayout {
            Repeater {
                model: [15, 30, 60]
                FlatButton { required property int modelData; text: modelData + " min"; onClicked: { app.sleepTimer.start(modelData); dialog.close(); } }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            AppField {
                id: minutes
                text: "30"
                Layout.fillWidth: true
                validator: IntValidator { bottom: 1; top: 1440 }
                inputMethodHints: Qt.ImhDigitsOnly
                Accessible.name: "Sleep timer minutes"
            }
            Label { text: "minutes"; color: Theme.muted }
            FlatButton { text: "Start"; enabled: minutes.acceptableInput; onClicked: { app.sleepTimer.start(Number(minutes.text)); dialog.close(); } }
        }
        Label { text: "Pauses local and Browse audio. Downloads continue. Uses your pause fade setting."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        FlatButton { text: "Cancel timer"; visible: app.sleepTimer.active; glyph: "x"; onClicked: { app.sleepTimer.cancel(); dialog.close(); } }
    }
}
