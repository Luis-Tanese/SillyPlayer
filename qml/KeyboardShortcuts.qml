import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    spacing: 16

    Label {
        text: "Keyboard shortcuts"
        color: Theme.text
        font.pixelSize: 20
        font.weight: Font.DemiBold
    }
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: shortcutsCard.implicitHeight + 36
        radius: 10
        color: Theme.surface
        border.width: 1
        border.color: Theme.separator
        ColumnLayout {
            id: shortcutsCard
            anchors.fill: parent
            anchors.margins: 18
            spacing: 12
            Label {
                text: "Playback shortcuts stay out of text fields and dialogs. Space and M control Browse audio when its player has focus; otherwise they control local music."
                color: Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
            Repeater {
                model: [
                    { keys: "Space", action: "Play / pause" },
                    { keys: "Ctrl + Left / Right", action: "Previous / next local song" },
                    { keys: "Left / Right", action: "Seek back / forward 5 seconds · player focused" },
                    { keys: "Up / Down", action: "Volume up / down 5% · player focused" },
                    { keys: "M", action: "Mute / unmute" },
                    { keys: "Ctrl + J", action: "Open / close the local playback queue" },
                    { keys: "Ctrl + K / Ctrl + F", action: "Jump to the search bar" },
                    { keys: "Esc", action: "Dismiss a popup, dialog or queue; leave fullscreen or the expanded player; cancel a drag or song selection; return from Browse playback" },
                    { keys: "Ctrl + click", action: "Toggle a song in the selection" },
                    { keys: "Shift + click", action: "Select a range from the last selected song" },
                    { keys: "Ctrl + Shift + click", action: "Add a range to the selection" },
                    { keys: "Ctrl + A", action: "Select all matching songs · song list focused" }
                ]
                delegate: RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 16
                    Item {
                        Layout.preferredWidth: Math.min(180, parent.width * 0.36)
                        implicitHeight: keyBadge.implicitHeight
                        Layout.alignment: Qt.AlignTop
                        Rectangle {
                            id: keyBadge
                            implicitWidth: Math.min(parent.width, keyLabel.implicitWidth + 16)
                            implicitHeight: keyLabel.implicitHeight + 8
                            radius: 6
                            color: Qt.alpha(Theme.hover, 0.5)
                            border.width: 1
                            border.color: Theme.separator
                            Label {
                                id: keyLabel
                                anchors.centerIn: parent
                                text: modelData.keys
                                color: Theme.text
                                font.pixelSize: 12
                                font.weight: Font.Medium
                            }
                        }
                    }
                    Label {
                        text: modelData.action
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                        Layout.alignment: Qt.AlignVCenter
                    }
                }
            }
            Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
            Label {
                text: "Select songs enables checkboxes. Select all toggles to Deselect all when every matching song is selected. Hardware Play/Pause, Next and Previous use desktop media integration where supported."
                color: Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
        }
    }
}
