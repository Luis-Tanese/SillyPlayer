import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    spacing: 16
    Label { text: "Backup and restore"; color: Theme.text; font.pixelSize: 20; font.weight: Font.DemiBold }
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: backupCard.implicitHeight + 36
        radius: 10
        color: Theme.surface
        border.width: 1
        border.color: Theme.separator
        ColumnLayout {
            id: backupCard
            anchors.fill: parent
            anchors.margins: 18
            spacing: 14
            Label {
                text: "ZIP archive"
                color: Theme.text
                font.pixelSize: 15
                font.weight: Font.Medium
            }
            Label {
                text: "Includes audio, playlists, likes, covers, source links, and song modifiers. Settings, listening statistics, and histories stay on this device."
                color: Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Flow {
                Layout.fillWidth: true
                spacing: 10
                FlatButton { text: "Back up library"; glyph: "download"; outlined: true; enabled: !app.backup.busy; onClicked: app.backup.chooseBackup() }
                FlatButton { text: "Restore library"; glyph: "folder"; outlined: true; enabled: !app.backup.busy; onClicked: app.backup.chooseRestore(app.settings.musicDir) }
            }
            Label {
                visible: app.backup.state !== "Idle"
                text: app.backup.message
                color: app.backup.state === "Failed" ? Theme.danger : Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            AppProgress {
                visible: app.backup.busy && app.backup.state !== "Backup ready" && app.backup.state !== "Restore ready"
                Layout.fillWidth: true
                indeterminate: app.backup.progress < 0
                value: Math.max(0, app.backup.progress)
            }
            Label {
                visible: app.backup.state === "Restore ready"
                text: (app.backup.preview.collections || 0) + " collections · merges into " + app.settings.musicDir + "/Restored. Existing edits and playlist order are kept."
                color: Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Label {
                visible: (app.backup.state === "Backup ready" || app.backup.state === "Restore ready") && (app.backup.preview.omissions || []).length > 0
                text: (app.backup.preview.omissions || []).map(item => (item.title || item.id) + ": " + item.reason).join("\n")
                color: Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Flow {
                Layout.fillWidth: true
                spacing: 10
                visible: app.backup.busy
                FlatButton {
                    visible: app.backup.state === "Backup ready" || app.backup.state === "Restore ready"
                    text: app.backup.state === "Backup ready" ? ((app.backup.preview.omissions || []).length ? "Back up available files" : "Create backup") : "Merge library"
                    glyph: "check"
                    primary: true
                    onClicked: app.backup.confirm()
                }
                FlatButton { text: "Cancel"; glyph: "x"; outlined: true; onClicked: app.backup.cancel() }
            }
        }
    }
}
