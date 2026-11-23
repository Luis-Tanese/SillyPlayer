import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import SillyPlayer 1.0

AppDialog {
    id: dialog
    objectName: "libraryToolsDialog"
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(parent.width - 40, 740)
    height: Math.min(parent.height - 40, 650)
    title: "Library tools"
    standardButtons: Dialog.Close
    property string pendingHash: ""
    property string pendingKeep: ""
    property string pendingTitle: ""
    Connections { target: app.libraryTools; function onOpened() { dialog.open(); } }
    onClosed: app.libraryTools.cancel()
    ColumnLayout {
        anchors.fill: parent
        spacing: 10
        RowLayout {
            Layout.fillWidth: true
            FlatButton { text: "File availability"; checked: app.libraryTools.view !== "duplicates"; enabled: !app.libraryTools.busy; onClicked: app.libraryTools.findMissing() }
            FlatButton { text: "Exact duplicates"; checked: app.libraryTools.view === "duplicates"; enabled: !app.libraryTools.busy; onClicked: app.libraryTools.findDuplicates() }
            Item { Layout.fillWidth: true }
            FlatButton { text: "Cancel"; visible: app.libraryTools.canCancel; onClicked: app.libraryTools.cancel() }
        }
        Label {
            text: app.libraryTools.message
            color: Theme.muted
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
            Layout.maximumHeight: 130
            elide: Text.ElideRight
        }
        AppProgress { visible: app.libraryTools.busy; Layout.fillWidth: true; indeterminate: app.libraryTools.progress < 0; value: Math.max(0, app.libraryTools.progress) }
        RowLayout {
            visible: app.libraryTools.view === "recover" && app.libraryTools.candidates.length > 0
            Layout.fillWidth: true
            FlatButton { text: "Select / deselect matches"; enabled: !app.libraryTools.busy; onClicked: app.libraryTools.selectCandidates() }
            Item { Layout.fillWidth: true }
            FlatButton {
                objectName: "confirmLibraryRecovery"
                text: "Reconnect selected"
                glyph: "check"
                enabled: !app.libraryTools.busy && app.libraryTools.candidates.some(t => t.selected)
                onClicked: recoverConfirm.open()
            }
        }
        ListView {
            id: list
            objectName: "libraryToolsList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 12
            model: StableListModel { rows: app.libraryTools.view === "duplicates" ? app.libraryTools.groups : app.libraryTools.view === "recover" ? app.libraryTools.candidates : app.libraryTools.missing }
            ScrollBar.vertical: ScrollBar {}
            delegate: Item {
                id: entry
                required property var record
                width: list.width
                height: loader.item ? loader.item.implicitHeight + 12 : 0
                Loader { id: loader; width: parent.width; sourceComponent: app.libraryTools.view === "duplicates" ? duplicateGroup : app.libraryTools.view === "recover" ? recoveryRow : missingRow }
                Component {
                    id: missingRow
                    ColumnLayout {
                        width: entry.width
                        spacing: 5
                        RowLayout {
                            Layout.fillWidth: true
                            Label { text: entry.record.title || "Untitled"; color: Theme.text; Layout.fillWidth: true; elide: Text.ElideRight }
                            FlatButton { text: "Remove from library"; enabled: !app.libraryTools.busy; onClicked: { app.removeLibraryTrack(entry.record.id); app.libraryTools.findMissing(); } }
                            FlatButton { text: "Locate"; glyph: "folder"; enabled: !app.libraryTools.busy; onClicked: app.libraryTools.chooseFile(entry.record.id) }
                        }
                        Label { text: entry.record.availability + " · " + entry.record.path; color: Theme.muted; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true; font.pixelSize: 11 }
                    }
                }
                Component {
                    id: recoveryRow
                    RowLayout {
                        width: entry.width
                        spacing: 10
                        AppCheck { checked: entry.record.selected; enabled: entry.record.eligible && !app.libraryTools.busy; Accessible.name: "Reconnect " + entry.record.track.title; onClicked: app.libraryTools.toggleCandidate(entry.record.track.id) }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4
                            Label { text: entry.record.track.title || "Untitled"; color: Theme.text; Layout.fillWidth: true; elide: Text.ElideRight }
                            Label { text: entry.record.path; color: Theme.muted; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true; font.pixelSize: 11 }
                            Label { text: entry.record.reason; color: entry.record.eligible ? Theme.muted : Theme.danger; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.pixelSize: 11 }
                        }
                    }
                }
                Component {
                    id: duplicateGroup
                    ColumnLayout {
                        id: group
                        width: entry.width
                        property string keep: entry.record.keep
                        spacing: 8
                        Label { text: entry.record.tracks.length + " identical files · choose the copy to keep"; color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true }
                        Repeater {
                            model: entry.record.tracks
                            RowLayout {
                                required property var modelData
                                Layout.fillWidth: true
                                spacing: 10
                                AppCheck { checked: group.keep === modelData.id; enabled: !app.libraryTools.busy; Accessible.name: "Keep " + modelData.title; onClicked: group.keep = modelData.id }
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 3
                                    Label { text: modelData.title + " · " + (modelData.artist || "Unknown artist"); color: Theme.text; Layout.fillWidth: true; elide: Text.ElideRight }
                                    Label { text: modelData.path; color: Theme.muted; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true; font.pixelSize: 11 }
                                }
                            }
                        }
                        FlatButton {
                            text: "Consolidate and Trash extras"; glyph: "trash-2"; enabled: !app.libraryTools.busy
                            onClicked: {
                                dialog.pendingHash = entry.record.hash;
                                dialog.pendingKeep = group.keep;
                                dialog.pendingTitle = entry.record.tracks.find(t => t.id === group.keep).title;
                                mergeConfirm.open();
                            }
                        }
                        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
                    }
                }
            }
        }
    }
    AppDialog {
        id: recoverConfirm
        objectName: "libraryRecoveryConfirmation"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(parent.width - 40, 460)
        title: "Reconnect selected songs?"
        standardButtons: Dialog.Cancel | Dialog.Ok
        contentItem: Label { width: recoverConfirm.availableWidth; text: "The selected files will replace missing paths. Existing song IDs, metadata, playlists, likes and speed settings are kept. Review matches without a stored checksum carefully. A moved folder's registered path will also be updated."; color: Theme.text; wrapMode: Text.WordWrap }
        onAccepted: app.libraryTools.recover()
    }
    AppDialog {
        id: mergeConfirm
        objectName: "libraryMergeConfirmation"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(parent.width - 40, 460)
        title: "Keep one file and consolidate?"
        standardButtons: Dialog.Cancel | Dialog.Ok
        contentItem: Label { width: mergeConfirm.availableWidth; text: "Keep: " + dialog.pendingTitle + "\n\nPlaylist entries, likes, queue references and listening counts will refer to this song. Its metadata and speed settings win. Extra audio files go to system Trash; nothing is permanently deleted."; color: Theme.text; wrapMode: Text.WordWrap }
        onAccepted: app.libraryTools.consolidate(dialog.pendingHash, dialog.pendingKeep)
    }
}
