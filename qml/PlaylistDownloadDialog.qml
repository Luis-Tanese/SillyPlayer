import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import SillyPlayer 1.0

AppDialog {
    id: dialog
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(parent.width - 40, 720)
    height: Math.min(parent.height - 40, 690)
    title: online.playlist.title || "Download YouTube playlist / mix"
    standardButtons: Dialog.Close
    property bool createLocal: false
    onClosed: online.playlist.dismiss()
    Connections {
        target: online.playlist
        function onOpened() {
            link.text = online.playlist.url;
            destination.text = app.settings.musicDir;
            again.checked = false;
            dialog.createLocal = false;
            localName.text = online.playlist.title || "Downloaded playlist";
            dialog.open();
        }
    }
    ScrollView {
        id: form
        anchors.fill: parent
        contentWidth: availableWidth
        clip: true
    ColumnLayout {
        width: form.availableWidth
        spacing: 10
        RowLayout {
            Layout.fillWidth: true
            AppField {
                id: link
                objectName: "playlistDownloadUrl"
                Layout.fillWidth: true
                placeholderText: "Paste a YouTube playlist or mix URL"
                onAccepted: online.playlist.load(text)
            }
            FlatButton { text: "Load"; enabled: !online.playlist.loading && !!link.text.trim(); onClicked: online.playlist.load(link.text) }
        }
        Label {
            Layout.fillWidth: true
            text: online.playlist.mix ? "Mix snapshot: YouTube may change its order or available songs. Only selected, loaded entries will download." : "Choose songs to download. More entries load in batches of 50. Availability is checked when each download starts."
            color: Theme.muted
            wrapMode: Text.WordWrap
        }
        RowLayout {
            Layout.fillWidth: true
            Label { text: online.playlist.selectedCount + " selected · " + online.playlist.entries.length + " loaded"; color: Theme.text; Layout.fillWidth: true }
            FlatButton {
                objectName: "playlistSelectAll"
                text: online.playlist.allSelected ? "Deselect all" : "Select all"
                enabled: online.playlist.entries.length > 0
                onClicked: online.playlist.toggleAll()
            }
        }
        ListView {
            id: list
            objectName: "playlistDownloadEntries"
            Layout.fillWidth: true
            Layout.preferredHeight: Math.max(120, dialog.availableHeight - 400)
            clip: true
            spacing: 3
            model: StableListModel { rows: online.playlist.entries }
            ScrollBar.vertical: ScrollBar {}
            delegate: Rectangle {
                id: row
                required property var record
                width: list.width
                height: 68
                radius: 7
                color: record.selected ? Qt.alpha(Theme.accent, 0.10) : hover.hovered ? Qt.alpha(Theme.hover, 0.5) : "transparent"
                HoverHandler { id: hover }
                MouseArea { anchors.fill: parent; enabled: row.record.selectable; onClicked: online.playlist.toggle(row.record.key) }
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 8
                    spacing: 10
                    AppCheck {
                        checked: row.record.selected
                        enabled: row.record.selectable
                        Accessible.name: "Select " + row.record.title
                        onClicked: online.playlist.toggle(row.record.key)
                    }
                    Art {
                        implicitWidth: 44; implicitHeight: 44
                        RemoteThumbnail { anchors.fill: parent; candidates: row.record.thumbnailCandidates || [] }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 3
                        Label { text: row.record.position + ". " + row.record.title; color: row.record.selectable ? Theme.text : Theme.muted; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label { text: row.record.selectable ? (row.record.uploader || "Unknown uploader") : row.record.reason; color: Theme.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                    }
                    Label { text: row.record.duration > 0 ? app.time(row.record.duration) : ""; color: Theme.muted; font.pixelSize: 11 }
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            AppBusy { visible: online.playlist.loading; running: visible; implicitWidth: 20; implicitHeight: 20 }
            Label { text: online.playlist.message; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            FlatButton {
                text: online.playlist.hasMore ? "Load more" : "Retry"
                visible: online.playlist.hasMore || (online.playlist.url && !online.playlist.entries.length)
                enabled: !online.playlist.loading
                onClicked: online.playlist.loadMore()
            }
            FlatButton { text: "Cancel loading"; visible: online.playlist.loading; onClicked: online.playlist.cancel() }
        }
        RowLayout {
            Layout.fillWidth: true
            AppCombo { id: format; model: ["Original audio", "MP3"]; Layout.preferredWidth: 170 }
            Label { text: "Best source audio"; color: Theme.muted; Layout.fillWidth: true }
        }
        RowLayout {
            Layout.fillWidth: true
            AppField { id: destination; Layout.fillWidth: true; placeholderText: "Download folder"; Accessible.name: "Playlist download folder" }
            FlatButton { text: "Change"; glyph: "folder"; onClicked: { const path = app.chooseFolder(destination.text); if (path) destination.text = path; } }
        }
        FlatButton {
            objectName: "createDownloadedPlaylist"
            text: "Create a local playlist"
            glyph: "list-music"
            checked: dialog.createLocal
            onClicked: { dialog.createLocal = !dialog.createLocal; if (dialog.createLocal) localName.text = online.playlist.title || "Downloaded playlist"; }
        }
        AppField {
            id: localName
            objectName: "downloadedPlaylistName"
            visible: dialog.createLocal
            Layout.fillWidth: true
            placeholderText: "Playlist name"
            Accessible.name: "Local playlist name"
        }
        Label {
            visible: dialog.createLocal
            text: "Selected songs are added in source order as downloads finish. Existing downloaded songs are reused; failed songs stay out until a retry succeeds."
            color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true
        }
        AppCheck { id: again; text: "Download completed songs again" }
        FlatButton {
            objectName: "downloadPlaylistSelection"
            Layout.alignment: Qt.AlignRight
            text: dialog.createLocal ? "Download and create playlist" : "Download " + online.playlist.selectedCount + " selected"
            glyph: "download"
            primary: true
            enabled: online.playlist.selectedCount > 0 && !online.playlist.loading && !!destination.text.trim() && (!dialog.createLocal || !!localName.text.trim())
            onClicked: online.playlist.download(format.currentIndex === 0 ? "Original" : "MP3", destination.text, again.checked, dialog.createLocal ? localName.text.trim() : "")
        }
    }
    }
}
