import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: actions
    objectName: "bulkActions"
    required property var pane
    required property var shell
    property var pendingRows: []
    property string failureText: ""
    property var cleanPreview: []
    property bool modalActive: picker.opened || trashConfirm.opened || failures.opened || batchTagsDialog.opened || cleanTitlesDialog.opened
    function ids() { return pendingRows.map(t => t.id); }
    function report(result, verb) {
        if (result.status === "error") {
            failureText = result.message || "Could not save changes";
            failures.open();
            return false;
        }
        const count = result.added === undefined ? result.removed : result.added;
        pane.resultMessage = count + " " + verb + (result.skipped ? " · " + result.skipped + " skipped" : "");
        return true;
    }
    function run(action) {
        pendingRows = pane.selectedRows.slice();
        if (!pendingRows.length) return;
        if (action === "playlist") { search.text = ""; picker.open(); }
        else if (action === "next" || action === "queue") report(app.queueTracks(ids(), action === "next"), "queued");
        else if (action === "remove") {
            const result = pane.collectionId === "liked" ? app.unlikeTracks(ids()) : app.removeMembers(pane.collectionId, pendingRows.map(t => t.memberId));
            report(result, "removed from collection");
        } else if (action === "library") {
            const result = app.removeLibraryTracks(ids());
            if (report(result, "removed from library") && result.failures.length) {
                failureText = result.failures.map(f => f.id + ": " + f.error).join("\n"); failures.open();
            }
        } else if (action === "editTags") {
            batchArtist.text = "";
            batchAlbum.text = "";
            batchAlbumArtist.text = "";
            batchTagsDialog.coverPath = "";
            batchTagsDialog.open();
        } else if (action === "cleanTitles") {
            const changes = [];
            const seen = ({});
            for (const row of pendingRows) {
                if (seen[row.id]) continue;
                seen[row.id] = true;
                const oldTitle = row.title || "";
                const cleaned = app.cleanTitle(oldTitle);
                if (cleaned && cleaned !== oldTitle) changes.push({id: row.id, before: oldTitle, after: cleaned});
            }
            cleanPreview = changes;
            if (!changes.length) {
                pane.resultMessage = "All selected titles are already clean";
                return;
            }
            cleanTitlesDialog.open();
        } else if (action === "trash") trashConfirm.open();
    }
    function addTo(collection, policy) {
        const result = app.addTracksToCollection(collection, ids(), policy || "auto");
        if (report(result, "added")) picker.close();
    }
    Connections {
        target: app
        function onBatchTrashFinished(result) {
            pane.resultMessage = result.successful.length + " songs moved to Trash" + (result.failures.length ? " · " + result.failures.length + " failed" : "");
            if (result.failures.length) {
                actions.failureText = result.failures.map(f => (f.title || f.id || "Library") + ": " + f.error).join("\n\n");
                failures.open();
            }
            if (!app.current.id) actions.shell.expanded = false;
        }
    }
    Connections {
        target: library
        function onBatchEditFinished(result) {
            pane.resultMessage = result.message || "Tags updated";
            if (result.failures && result.failures.length) {
                actions.failureText = result.failures.map(f => (f.title || f.id || "Library") + ": " + f.error).join("\n\n");
                failures.open();
            }
        }
    }
    AppDialog {
        id: picker
        objectName: "bulkPlaylistDialog"
        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(actions.shell.width - 40, 460)
        title: "Add to Playlist"
        standardButtons: Dialog.Close
        property string pendingCreateName: ""
        property bool enteringCustomName: false
        onOpened: { search.text = ""; pendingCreateName = ""; enteringCustomName = false; customPlaylistName.text = ""; }
        ColumnLayout {
            width: parent.width; spacing: 10
            Label { text: actions.pendingRows.length + (actions.pendingRows.length === 1 ? " selected song" : " selected songs"); color: Theme.muted }
            AppField {
                id: search; search: true; placeholderText: "Find or name a playlist"; Layout.fillWidth: true
                onTextChanged: if (picker.pendingCreateName) picker.pendingCreateName = ""
            }
            CollectionRow {
                Layout.fillWidth: true
                visible: !picker.pendingCreateName && !picker.enteringCustomName
                title: search.text.trim() ? 'Create playlist "' + search.text.trim() + '"' : "Create playlist"
                subtitle: search.text.trim() ? "Use this name & add selected songs" : "Type a name above or tap to enter"
                glyph: "plus"
                onClicked: {
                    const typed = search.text.trim();
                    if (typed.length > 0) picker.pendingCreateName = typed;
                    else { customPlaylistName.text = ""; picker.enteringCustomName = true; customPlaylistName.forceActiveFocus(); }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                visible: !!picker.pendingCreateName
                implicitHeight: confirmCreateCol.implicitHeight + 24
                radius: 10; color: Qt.alpha(Theme.accent, 0.08); border.width: 1; border.color: Qt.alpha(Theme.accent, 0.35)
                ColumnLayout {
                    id: confirmCreateCol
                    anchors.fill: parent; anchors.margins: 12; spacing: 10
                    Label {
                        text: 'Create playlist "' + picker.pendingCreateName + '" and add ' + actions.pendingRows.length + (actions.pendingRows.length === 1 ? " song?" : " songs?")
                        color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.weight: Font.Medium
                    }
                    RowLayout {
                        Layout.alignment: Qt.AlignRight; spacing: 8
                        FlatButton { text: "Edit name"; onClicked: { customPlaylistName.text = picker.pendingCreateName; picker.pendingCreateName = ""; picker.enteringCustomName = true; customPlaylistName.forceActiveFocus(); } }
                        FlatButton { text: "Cancel"; onClicked: picker.pendingCreateName = "" }
                        FlatButton {
                            text: "Create & add"; glyph: "check"; primary: true
                            onClicked: {
                                const id = app.createCollection(picker.pendingCreateName, "playlist");
                                picker.pendingCreateName = "";
                                if (id) actions.addTo(id, "auto");
                            }
                        }
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                visible: picker.enteringCustomName
                implicitHeight: customCreateCol.implicitHeight + 24
                radius: 10; color: Theme.surface; border.width: 1; border.color: Theme.separator
                ColumnLayout {
                    id: customCreateCol
                    anchors.fill: parent; anchors.margins: 12; spacing: 10
                    AppField {
                        id: customPlaylistName; placeholderText: "New playlist name"; Layout.fillWidth: true
                        onAccepted: if (text.trim().length > 0) {
                            const id = app.createCollection(text.trim(), "playlist");
                            picker.enteringCustomName = false;
                            if (id) actions.addTo(id, "auto");
                        }
                    }
                    RowLayout {
                        Layout.alignment: Qt.AlignRight; spacing: 8
                        FlatButton { text: "Cancel"; onClicked: picker.enteringCustomName = false }
                        FlatButton {
                            text: "Create & add"; glyph: "check"; primary: true
                            enabled: customPlaylistName.text.trim().length > 0
                            onClicked: {
                                const id = app.createCollection(customPlaylistName.text.trim(), "playlist");
                                picker.enteringCustomName = false;
                                if (id) actions.addTo(id, "auto");
                            }
                        }
                    }
                }
            }
            ListView {
                Layout.fillWidth: true; Layout.preferredHeight: Math.max(80, Math.min(300, actions.shell.height - 360)); clip: true
                model: {
                    if (!picker.visible) return [];
                    const editable = app.collections.filter(c => c.kind === "playlist" || c.kind === "custom");
                    const pinned = editable.filter(c => !!c.pinned);
                    const rest = editable.filter(c => !c.pinned);
                    return pinned.concat([{id:"liked",name:"Liked Songs",kind:"liked",count:library.likedCount}], rest)
                        .filter(c => c.name.toLowerCase().includes(search.text.toLowerCase()));
                }
                ScrollBar.vertical: ScrollBar {}
                delegate: CollectionRow {
                    required property var modelData
                    width: ListView.view.width
                    title: modelData.name; cover: modelData.cover || ""; glyph: modelData.kind === "liked" ? "heart" : ""
                    pinned: !!modelData.pinned
                    subtitle: (modelData.count || 0) + " songs"
                    onClicked: actions.addTo(modelData.id, "auto")
                }
                Label { anchors.centerIn: parent; text: "No matching playlists"; visible: parent.count === 0; color: Theme.muted }
            }
        }
    }
    AppDialog {
        id: trashConfirm
        objectName: "bulkTrashDialog"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(actions.shell.width - 40, 460)
        title: "Move selected songs to Trash?"; standardButtons: Dialog.Cancel
        ColumnLayout {
            width: parent.width; spacing: 14
            Label {
                text: {
                    const paths = ({}); actions.pendingRows.forEach(t => paths[t.path] = true);
                    return actions.pendingRows.length + " selected entries · " + Object.keys(paths).length + " audio files";
                }
                color: Theme.text; wrapMode: Text.Wrap; Layout.fillWidth: true
            }
            Label { text: "Files go to the system Trash. Empty Trash to delete them permanently."; color: Theme.muted; wrapMode: Text.Wrap; Layout.fillWidth: true }
            FlatButton {
                objectName: "confirmBulkTrash"; text: "Move to Trash"; glyph: "trash-2"; Layout.alignment: Qt.AlignRight
                enabled: !library.trashing
                onClicked: {
                    if (app.trashTracks(actions.ids())) { trashConfirm.close(); actions.pane.resultMessage = "Moving songs to Trash…"; }
                }
            }
        }
    }
    AppDialog {
        id: batchTagsDialog
        objectName: "bulkEditTagsDialog"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(actions.shell.width - 40, 460)
        title: "Batch edit tags"
        standardButtons: Dialog.Save | Dialog.Cancel
        property string coverPath: ""
        ColumnLayout {
            width: parent.width; spacing: 8
            Label { text: actions.pendingRows.length + " selected songs · leave a field blank to keep each song's current value"; color: Theme.muted; wrapMode: Text.Wrap; Layout.fillWidth: true; font.pixelSize: 12 }
            Label { text: "Artist / Creator"; color: Theme.muted }
            AppField { id: batchArtist; placeholderText: "Keep existing"; Layout.fillWidth: true }
            Label { text: "Album"; color: Theme.muted }
            AppField { id: batchAlbum; placeholderText: "Keep existing"; Layout.fillWidth: true }
            Label { text: "Album artist"; color: Theme.muted }
            AppField { id: batchAlbumArtist; placeholderText: "Keep existing"; Layout.fillWidth: true }
            FlatButton {
                text: batchTagsDialog.coverPath ? "Artwork selected" : "Set shared artwork"
                glyph: "image"
                onClicked: {
                    const p = app.chooseImage();
                    if (p) batchTagsDialog.coverPath = p;
                }
            }
        }
        onAccepted: {
            const tags = ({});
            if (batchArtist.text.trim().length > 0) tags.artist = batchArtist.text.trim();
            if (batchAlbum.text.trim().length > 0) tags.album = batchAlbum.text.trim();
            if (batchAlbumArtist.text.trim().length > 0) tags.albumArtist = batchAlbumArtist.text.trim();
            if (batchTagsDialog.coverPath) tags.cover = batchTagsDialog.coverPath;
            if (!Object.keys(tags).length) return;
            if (library.editTagsBatch(actions.ids(), tags))
                actions.pane.resultMessage = "Updating tags for " + actions.pendingRows.length + " songs…";
        }
    }
    AppDialog {
        id: cleanTitlesDialog
        objectName: "bulkCleanTitlesDialog"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(actions.shell.width - 40, 520)
        title: "Clean song titles?"
        standardButtons: Dialog.Cancel
        ColumnLayout {
            width: parent.width; spacing: 10
            Label {
                text: actions.cleanPreview.length + " of " + actions.pendingRows.length + " selected songs have video/audio tags (such as Official Audio, Official Video, Lyrics) that will be stripped."
                color: Theme.text; wrapMode: Text.Wrap; Layout.fillWidth: true
            }
            ScrollView {
                Layout.fillWidth: true; Layout.preferredHeight: Math.min(220, actions.cleanPreview.length * 42 + 8)
                clip: true; contentWidth: availableWidth
                ColumnLayout {
                    width: parent.width; spacing: 6
                    Repeater {
                        model: actions.cleanPreview.slice(0, 20)
                        ColumnLayout {
                            required property var modelData
                            Layout.fillWidth: true; spacing: 1
                            Label { text: modelData.before; color: Theme.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                            Label { text: "→ " + modelData.after; color: Theme.accent; font.pixelSize: 12; elide: Text.ElideRight; Layout.fillWidth: true }
                        }
                    }
                }
            }
            FlatButton {
                text: "Clean " + actions.cleanPreview.length + (actions.cleanPreview.length === 1 ? " title" : " titles")
                glyph: "check"; primary: true; Layout.alignment: Qt.AlignRight
                onClicked: {
                    cleanTitlesDialog.close();
                    if (library.cleanTitles(actions.cleanPreview.map(c => c.id)))
                        actions.pane.resultMessage = "Cleaning " + actions.cleanPreview.length + " titles…";
                }
            }
        }
    }
    AppDialog {
        id: failures
        objectName: "bulkFailureDialog"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(actions.shell.width - 40, 520)
        title: "Some changes could not be completed"; standardButtons: Dialog.Close
        ScrollView {
            width: parent.width; height: Math.max(80, Math.min(280, actions.shell.height - 220)); clip: true; contentWidth: availableWidth
            Label { width: parent.width; text: actions.failureText; wrapMode: Text.Wrap; color: Theme.text }
        }
    }
}
