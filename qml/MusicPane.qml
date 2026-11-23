import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import SillyPlayer 1.0

Item {
    id: pane
    objectName: "musicPane"
    required property var shell
    property string tab: "Songs"
    property string albumView: "Generated"
    property string collectionId: ""
    property string collectionName: ""
    property bool editMode: false
    property var selectedCollection: {
        let observed = revision;
        return collectionId ? app.collections.find(c => c.id === collectionId) || ({}) : ({});
    }
    property bool editable: !!selectedCollection.id && !["generated", "artist"].includes(selectedCollection.kind)
    onCollectionIdChanged: { editMode = false; clearSelection(); cancelReorder(); filter.text = ""; songs.contentY = 0; }
    onTabChanged: { clearSelection(); cancelReorder(); }
    property bool selectionMode: false
    property var selectedKeys: ({})
    property string selectionAnchor: ""
    property var selectedRows: orderedTracks.filter(t => !!selectedKeys[rowKey(t)])
    property int selectedCount: selectedRows.length
    property string resultMessage: ""
    property bool bulkModalActive: bulkActions.modalActive
    property bool listKeyboard: {
        let item = shell.activeFocusItem;
        while (item) {
            if (item === songs || item === selectToggle || item === bulkToolbar) return true;
            item = item.parent;
        }
        return false;
    }
    function rowKey(track) { return track.memberId || track.id; }
    function clearSelection() { selectedKeys = ({}); selectionMode = false; selectionAnchor = ""; }
    readonly property bool allSelected: visibleTracks.length > 0 && visibleTracks.every(t => !!selectedKeys[rowKey(t)])
    function toggleAll() {
        if (allSelected) { let keys = Object.assign({}, selectedKeys); visibleTracks.forEach(t => delete keys[rowKey(t)]); selectedKeys = keys; selectionAnchor = ""; }
        else selectAll();
    }
    function selectAll() {
        selectionMode = true; cancelReorder();
        let keys = Object.assign({}, selectedKeys); visibleTracks.forEach(t => keys[rowKey(t)] = true); selectedKeys = keys;
        songs.forceActiveFocus();
    }
    function rowClick(track, modifiers) {
        songs.forceActiveFocus();
        const modified = !!(modifiers & (Qt.ControlModifier | Qt.ShiftModifier));
        if (!selectionMode && !modified) return false;
        selectionMode = true; cancelReorder();
        let keys = Object.assign({}, selectedKeys);
        const key = rowKey(track);
        const at = visibleTracks.findIndex(t => rowKey(t) === key);
        const anchor = visibleTracks.findIndex(t => rowKey(t) === selectionAnchor);
        if ((modifiers & Qt.ShiftModifier) && anchor >= 0) {
            if (!(modifiers & Qt.ControlModifier)) keys = ({});
            for (let i=Math.min(at,anchor); i<=Math.max(at,anchor); ++i) keys[rowKey(visibleTracks[i])] = true;
        } else {
            if (keys[key]) delete keys[key]; else keys[key] = true;
            selectionAnchor = key;
        }
        selectedKeys = keys;
        return true;
    }
    onVisibleTracksChanged: {
        let keys = ({}); orderedTracks.forEach(t => { if (selectedKeys[rowKey(t)]) keys[rowKey(t)] = true; });
        selectedKeys = keys;
        cancelReorder();
    }
    property string dragMember: ""
    property int dragOrigin: -1
    property int dropSlot: -1
    property real dragY: 0
    function updateDrop(scenePoint) {
        if (!dragMember) return;
        dragY = songs.mapFromItem(null, scenePoint.x, scenePoint.y).y;
        dropSlot = Math.max(0, Math.min(songs.count, Math.round((songs.contentY + dragY) / 75)));
    }
    function cancelReorder() { dragMember = ""; dragOrigin = -1; dropSlot = -1; }
    function finishReorder() {
        if (!dragMember) return;
        const member = dragMember, from = dragOrigin, slot = dropSlot;
        cancelReorder();
        if (slot >= 0) app.moveMember(collectionId, member, slot > from ? slot - 1 : slot);
    }
    Timer {
        interval: 16; running: !!pane.dragMember; repeat: true
        onTriggered: {
            const movement = pane.dragY < 36 ? -9 : pane.dragY > songs.height - 36 ? 9 : 0;
            if (!movement) return;
            songs.contentY = Math.max(0, Math.min(Math.max(0, songs.contentHeight - songs.height), songs.contentY + movement));
            pane.dropSlot = Math.max(0, Math.min(songs.count, Math.round((songs.contentY + pane.dragY) / 75)));
        }
    }
    BulkActions { id: bulkActions; pane: pane; shell: pane.shell }
    function bulkAction(action) { bulkActions.run(action); }
    function focusSearch() {
        if (tab === "Playlists" && !collectionId) {
            collectionFilter.forceActiveFocus();
            collectionFilter.selectAll();
        } else {
            if (tab !== "Songs" && !collectionId) tab = "Songs";
            filter.forceActiveFocus();
            filter.selectAll();
        }
    }

    property int revision: 0
    property int likedCount: library.likedCount
    property int recentCount: app.recentCount
    property string appliedQuery: ""
    property var tracksById: ({})
    onOrderedTracksChanged: {
        const lookup = {};
        for(const row of orderedTracks) {
            if(!lookup[row.id])lookup[row.id]=[];
            lookup[row.id].push(row);
        }
        tracksById=lookup;
    }
    Timer { id: searchDelay; interval: 120; onTriggered: pane.appliedQuery = filter.text.toLowerCase() }
    property var orderedTracks: {
        let observed = revision;
        let list = (collectionId ? app.collectionTracks(collectionId) : library.tracks).slice();
        if (!collectionId && sort.currentIndex === 1)
            list.sort((a, b) => a.title.localeCompare(b.title));
        if (!collectionId && sort.currentIndex === 2)
            list.sort((a, b) => a.artist.localeCompare(b.artist));
        // keep a plain JS snapshot so field patches remain available when a later search/sort rebuilds the view, without invalidating that view now
        return list.map(row => Object.assign({},row));
    }
    property var visibleTracks: orderedTracks.filter(t => !appliedQuery || ((t.title || "") + " " + (t.artist || "") + " " + (t.album || "")).toLowerCase().includes(appliedQuery))
    property var matchingCollections: visibleCollections.filter(c => c.name.toLowerCase().includes(collectionFilter.text.toLowerCase()))
    property var visibleCollections: {
        let observed = revision;
        if(tab === "Songs" || collectionId)return [];
        return app.collections.filter(c => tab === "Playlists" ? c.kind === "playlist" : c.kind === (tab === "Artists" ? "artist" : albumView === "Generated" ? "generated" : "custom"));
    }
    Connections {
        target: app
        function onCollectionsChanged() {
            pane.revision++;
        }
        function onRecentChanged() { if(pane.collectionId === "recent") pane.revision++; }
    }
    Connections {
        target: library
        function onTracksUpdated(patches) {
            for(const id in patches)for(const row of pane.tracksById[id] || [])Object.assign(row,patches[id]);
            songModel.patchTracks(patches);
            // membership changes affect Liked Songs only
            // ordinary song lists, artist/album groups and the saved playback queue keep their order
            if(pane.collectionId === "liked") {
                for(const id in patches)if(patches[id].liked !== undefined){pane.revision++;break;}
            }
        }
    }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: pane.width > 700 ? 32 : 20
        spacing: 20
        visible: !shell.expanded
        RowLayout {
            Layout.fillWidth: true
            spacing: 6
            Flickable {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                contentWidth: musicNavigation.implicitWidth
                contentHeight: 40
                boundsBehavior: Flickable.StopAtBounds
                flickableDirection: Flickable.HorizontalFlick
                clip: true
                Row {
                    id: musicNavigation
                    spacing: 6
                    Repeater {
                        model: ["Songs", "Playlists", "Artists", "Albums"]
                        FlatButton {
                            required property string modelData
                            text: modelData
                            objectName: "musicNav-" + modelData
                            navigation: true
                            leftPadding: pane.width < 440 ? 8 : 13
                            rightPadding: leftPadding
                            checked: pane.tab === modelData
                            onClicked: {
                                pane.tab = modelData;
                                pane.collectionId = "";
                                filter.text = "";
                            }
                        }
                    }
                }
            }
            FlatButton {
                glyph: "folder"
                hint: "Add music folder"
                onClicked: {
                    let path = app.chooseFolder();
                    if (path)
                        library.addFolder(path);
                }
            }
        }
        RowLayout {
            visible: pane.tab === "Albums" && !pane.collectionId
            spacing: 8
            FlatButton {
                text: "Generated"
                checked: pane.albumView === "Generated"
                onClicked: pane.albumView = "Generated"
            }
            FlatButton {
                text: "Custom"
                checked: pane.albumView === "Custom"
                onClicked: pane.albumView = "Custom"
            }
        }
        RowLayout {
            visible: !!pane.collectionId
            Layout.fillWidth: true
            spacing: 8
            FlatButton {
                glyph: "arrow-left"
                hint: "Back to collections"
                onClicked: pane.collectionId = ""
            }
            Label {
                text: pane.selectedCollection.name || pane.collectionName
                color: Theme.text
                font.pixelSize: 18
                Layout.fillWidth: true
                elide: Text.ElideRight
            }
            FlatButton {
                glyph: "plus"; hint: "Add songs"; visible: pane.editable
                onClicked: shell.addSongs(pane.collectionId)
            }
            FlatButton {
                glyph: pane.editMode ? "check" : "pencil"; hint: pane.editMode ? "Finish editing" : "Edit tracks"
                visible: pane.editable; checked: pane.editMode
                onClicked: pane.editMode = !pane.editMode
            }
            FlatButton {
                glyph: "download"; hint: "Export playlist (.m3u8)"
                visible: !!pane.collectionId && pane.orderedTracks.length > 0
                onClicked: {
                    const res = app.exportPlaylistM3u(pane.collectionId);
                    if (res && res.message) pane.resultMessage = res.message;
                }
            }
            FlatButton {
                glyph: "ellipsis"; hint: "Playlist options"; visible: pane.editable
                onClicked: shell.collectionMenu(pane.selectedCollection, this)
            }
        }
        RowLayout {
            visible: pane.tab === "Songs" || !!pane.collectionId
            Layout.fillWidth: true
            FlatButton {
                id: selectToggle
                objectName: "selectSongs"
                text: pane.selectionMode ? "Done" : "Select songs"
                glyph: pane.selectionMode ? "check" : "list-checks"
                hint: pane.selectionMode ? "Finish selecting songs" : "Select songs for bulk actions. Ctrl-click toggles a song; Shift-click selects a range."
                checked: pane.selectionMode
                onClicked: { if (pane.selectionMode) pane.clearSelection(); else { pane.selectionMode = true; pane.cancelReorder(); songs.forceActiveFocus(); } }
            }
            AppField {
                id: filter
                objectName: "musicTrackSearch"
                onTextChanged: { pane.selectionAnchor = ""; pane.cancelReorder(); songs.contentY = 0; if (!text) { searchDelay.stop(); pane.appliedQuery = ""; } else searchDelay.restart(); }
                search: true
                Layout.fillWidth: true
                placeholderText: pane.collectionId ? (pane.editable || pane.collectionId === "liked" || pane.collectionId === "recent" ? "Search this playlist" : "Search this collection") : "Search your music"
                selectByMouse: true
            }
            AppCombo {
                id: sort
                onCurrentIndexChanged: { pane.clearSelection(); pane.cancelReorder(); }
                model: ["Recently added", "Title", "Artist"]
                Layout.preferredWidth: Math.min(150, pane.width * 0.28)
                visible: !pane.collectionId
            }
        }
        AppField {
            id: collectionFilter
            objectName: "playlistSearch"
            search: true
            placeholderText: "Find a playlist"
            visible: pane.tab === "Playlists" && !pane.collectionId
            Layout.fillWidth: true
        }
        FocusScope {
            id: bulkToolbar
            Layout.fillWidth: true
            implicitHeight: bulkFlow.implicitHeight
            visible: pane.selectionMode
            Flow {
                id: bulkFlow
                width: parent.width
                spacing: 4
                Label { text: pane.selectedCount + " selected"; color: Theme.muted; height: 36; verticalAlignment: Text.AlignVCenter; rightPadding: 8 }
                FlatButton { objectName: "selectAllSongs"; text: pane.allSelected ? "Deselect all" : "Select all"; enabled: pane.visibleTracks.length > 0; onClicked: pane.toggleAll() }
                FlatButton { objectName: "bulkAddPlaylist"; text: "Add to Playlist"; glyph: "list-music"; enabled: pane.selectedCount > 0; onClicked: pane.bulkAction("playlist") }
                FlatButton { objectName: "bulkPlayNext"; text: "Play Next"; glyph: "skip-forward"; enabled: pane.selectedCount > 0; onClicked: pane.bulkAction("next") }
                FlatButton { objectName: "bulkAddQueue"; text: "Add to Queue"; glyph: "list-music"; enabled: pane.selectedCount > 0; onClicked: pane.bulkAction("queue") }
                FlatButton { objectName: "bulkEditTags"; text: "Edit tags"; glyph: "pencil"; enabled: pane.selectedCount > 0 && !library.scanning && !library.trashing; onClicked: pane.bulkAction("editTags") }
                FlatButton { objectName: "bulkCleanTitles"; text: "Clean titles"; glyph: "check"; enabled: pane.selectedCount > 0 && !library.scanning && !library.trashing; onClicked: pane.bulkAction("cleanTitles") }
                FlatButton {
                    objectName: "bulkRemoveCollection"
                    text: pane.collectionId === "liked" ? "Remove from Liked Songs" : "Remove from collection"
                    glyph: "x"; visible: pane.editable || pane.collectionId === "liked"
                    enabled: pane.selectedCount > 0; onClicked: pane.bulkAction("remove")
                }
                FlatButton { text: "Remove from library"; enabled: pane.selectedCount > 0 && !library.scanning && !library.trashing; onClicked: pane.bulkAction("library") }
                FlatButton { objectName: "bulkTrash"; text: "Move selected to Trash"; glyph: "trash-2"; enabled: pane.selectedCount > 0 && !library.trashing && !app.backup.busy; onClicked: pane.bulkAction("trash") }
            }
        }
        RowLayout {
            visible: !!pane.resultMessage
            Layout.fillWidth: true
            Label { text: pane.resultMessage; color: Theme.muted; wrapMode: Text.Wrap; Layout.fillWidth: true; font.pixelSize: 12 }
            FlatButton { glyph: "x"; hint: "Dismiss"; onClicked: pane.resultMessage = "" }
        }
        ListView {
            id: songs
            objectName: "musicSongList"
            reuseItems: false
            visible: pane.tab === "Songs" || !!pane.collectionId
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 3
            model: StableListModel {
                id: songModel
                rows: pane.visibleTracks
            }
            ScrollBar.vertical: ScrollBar {
            }
            Rectangle {
                objectName: "playlistDropMarker"
                parent: songs
                z: 20; visible: !!pane.dragMember && pane.dropSlot >= 0
                x: 0; y: Math.max(0, Math.min(songs.height - 2, pane.dropSlot * 75 - songs.contentY))
                width: songs.width; height: 2; color: Theme.accent
            }
            delegate: RowLayout {
                id: memberRow
                required property var record
                required property int index
                width: songs.width
                height: 72
                spacing: 0
                FlatButton {
                    id: memberGrip
                    objectName: "memberGrip-" + memberRow.index
                    glyph: "grip-vertical"
                    visible: pane.editMode && pane.editable
                    enabled: !filter.text && !pane.selectionMode
                    hint: filter.text ? "Clear search to reorder" : pane.selectionMode ? "Finish selection to reorder" : "Drag to reorder"
                    checked: pane.dragMember === memberRow.record.memberId
                    MouseArea {
                        anchors.fill: parent
                        enabled: memberGrip.enabled
                        preventStealing: true
                        cursorShape: Qt.SizeVerCursor
                        property point startPoint
                        property bool began: false
                        onPressed: mouse => {
                            startPoint = mapToItem(null, mouse.x, mouse.y);
                            began = false;
                            songs.forceActiveFocus();
                        }
                        onPositionChanged: mouse => {
                            if (!pressed) return;
                            const point = mapToItem(null, mouse.x, mouse.y);
                            if (!began && Math.abs(point.y - startPoint.y) >= 8) {
                                began = true;
                                songs.currentIndex = memberRow.index;
                                pane.dragMember = memberRow.record.memberId;
                                pane.dragOrigin = memberRow.index;
                            }
                            if (began) pane.updateDrop(point);
                        }
                        onReleased: { pane.finishReorder(); began = false; }
                        onCanceled: { pane.cancelReorder(); began = false; }
                    }
                }
                TrackRow {
                    Layout.fillWidth: true
                    track: memberRow.record
                    selected: !!pane.selectedKeys[pane.rowKey(memberRow.record)]
                    selectionController: pane
                    shell: pane.shell
                    sourceTracks: pane.visibleTracks
                    sourceName: pane.collectionId ? (pane.selectedCollection.name || pane.collectionName) : "Songs"
                    sourceId: pane.collectionId
                }
                FlatButton {
                    objectName: "removeMember-" + memberRow.index
                    glyph: "x"; hint: "Remove this entry"; visible: pane.editMode && pane.editable && !pane.selectionMode
                    onClicked: app.removeMember(pane.collectionId, memberRow.record.memberId)
                }
            }
            Label {
                anchors.centerIn: parent
                width: parent.width - 30
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                visible: songs.count === 0
                text: filter.text ? "No matching songs" : library.scanning ? "Reading your music…" : library.tracks.length ? "No songs here yet" : "Bring your music along.\nAdd a folder to get started."
                color: Theme.muted
                lineHeight: 1.5
            }
        }
        ListView {
            id: collections
            visible: pane.tab !== "Songs" && !pane.collectionId
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: StableListModel {
                rows: pane.tab === "Playlists" ? pane.matchingCollections : pane.visibleCollections
            }
            ScrollBar.vertical: ScrollBar {
            }
            header: Column {
                width: collections.width
                CollectionRow {
                    width: parent.width
                    title: pane.tab === "Playlists" ? "New Playlist" : "New Custom Album"
                    glyph: "plus"
                    visible: pane.tab === "Playlists" || (pane.tab === "Albums" && pane.albumView === "Custom")
                    height: visible ? 68 : 0
                    onClicked: shell.newCollection(pane.tab === "Playlists" ? "playlist" : "custom")
                }
                CollectionRow {
                    objectName: "importPlaylistRow"
                    width: parent.width
                    title: "Import Playlist (.m3u / .m3u8)"
                    subtitle: "Match songs from your library"
                    glyph: "download"
                    visible: pane.tab === "Playlists" && !collectionFilter.text
                    height: visible ? 68 : 0
                    onClicked: {
                        const res = app.importPlaylistM3u();
                        if (res && res.message) pane.resultMessage = res.message;
                    }
                }
                CollectionRow {
                    width: parent.width
                    title: "Liked Songs"; subtitle: pane.likedCount + " songs"; glyph: "heart"
                    visible: pane.tab === "Playlists" && "liked songs".includes(collectionFilter.text.toLowerCase()); height: visible ? 68 : 0
                    onClicked: { pane.collectionId = "liked"; pane.collectionName = "Liked Songs"; }
                }
                CollectionRow {
                    objectName: "recentlyPlayedCollection"
                    width: parent.width
                    title: "Recently Played"; subtitle: pane.recentCount + " songs"; glyph: "rotate-ccw"
                    visible: pane.tab === "Playlists" && "recently played".includes(collectionFilter.text.toLowerCase()); height: visible ? 68 : 0
                    onClicked: { pane.collectionId = "recent"; pane.collectionName = "Recently Played"; }
                }
            }
            Label {
                anchors.horizontalCenter: parent.horizontalCenter
                y: collections.headerItem ? collections.headerItem.height + 24 : 24
                visible: pane.tab === "Playlists" && !!collectionFilter.text && pane.matchingCollections.length === 0 && !"liked songs".includes(collectionFilter.text.toLowerCase()) && !"recently played".includes(collectionFilter.text.toLowerCase())
                text: "No matching playlists"; color: Theme.muted
            }
            delegate: CollectionRow {
                required property var record
                width: collections.width
                title: record.name
                subtitle: (record.owner ? record.owner + " · " : "") + record.count + " songs"
                cover: record.cover || ""
                actions: !["generated", "artist"].includes(record.kind)
                onClicked: { pane.collectionId = record.id; pane.collectionName = record.name; }
                onMenuRequested: anchor => shell.collectionMenu(record, anchor)
            }
        }
    }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: pane.height < 550 ? 16 : 24
        visible: shell.expanded
        spacing: pane.height < 550 ? 4 : 8
        RowLayout {
            Layout.fillWidth: true
            FlatButton {
                glyph: "arrow-left"
                hint: "Back"
                onClicked: shell.collapse()
            }
            Item {
                Layout.fillWidth: true
            }
            FlatButton {
                glyph: "list-music"
                hint: "Playback queue"
                onClicked: shell.toggleQueue()
            }
            FlatButton {
                glyph: "ellipsis"
                hint: "Player options"
                onClicked: shell.playerMenu(this)
            }
        }
        Item {
            Layout.fillHeight: true
            Layout.minimumHeight: 0
        }
        Item {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Math.max(72, Math.min(pane.width - 100, pane.height - 390, 360))
            Layout.preferredHeight: Layout.preferredWidth
            Art { anchors.fill: parent; source: app.current.art || "" }
        }
        Item {
            implicitHeight: 10
        }
        Label {
            Layout.fillWidth: true
            text: app.current.title || "Choose a song"
            horizontalAlignment: Text.AlignHCenter
            font.pixelSize: pane.width > 700 ? 24 : 19
            color: Theme.text
            elide: Text.ElideRight
        }
        Label {
            Layout.fillWidth: true
            text: app.current.artist || ""
            horizontalAlignment: Text.AlignHCenter
            color: Theme.muted
            font.pixelSize: 14
        }
        Label {
            objectName: "expandedSpeedBadge"
            Layout.alignment: Qt.AlignHCenter
            text: app.modifierLabel(app.current)
            visible: !!text
            color: Theme.accent; font.pixelSize: 11
        }
        ColumnLayout {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Math.min(540, pane.width - 60)
            spacing: 0
            SeekBar {
                id: largeSeek
                Layout.fillWidth: true
                backend: music
            }
            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: app.time(largeSeek.displayedPosition)
                    color: Theme.muted
                    font.pixelSize: 11
                }
                Item {
                    Layout.fillWidth: true
                }
                Label {
                    text: music.duration > 0 ? app.time(music.duration) : "Unknown duration"
                    color: Theme.muted
                    font.pixelSize: 11
                }
            }
        }
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: 18
            FlatButton {
                glyph: app.mode === 2 ? "shuffle" : app.mode === 1 ? "repeat-1" : "repeat"
                hint: ["Repeat queue", "Repeat one", "Shuffle"][app.mode]
                onClicked: app.cycleMode()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: "skip-back"
                hint: "Previous"
                onClicked: app.previous()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: (music.paused || music.pausing) ? "play" : "pause"
                hint: "Play / Pause music"
                implicitWidth: 48
                implicitHeight: 48
                primary: true
                icon.width: 26
                icon.height: 26
                onClicked: app.toggle()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: "skip-forward"
                hint: "Next"
                onClicked: app.next()
            }
            FlatButton {
                glyph: "heart"
                hint: "Like song"
                checked: app.currentLiked
                onClicked: app.like(app.current.id)
            }
        }
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: 8
            FlatButton {
                glyph: music.volume > 0 ? "volume-2" : "volume-x"
                hint: "Mute music"
                onClicked: music.volume = music.volume > 0 ? 0 : 70
            }
            VolumeSlider {
                objectName: "expandedVolume"
                Layout.preferredWidth: Math.min(180, pane.width - 130)
                backend: music
                Accessible.name: "Music volume"
            }
        }
        Label {
            Layout.alignment: Qt.AlignHCenter
            text: music.error
            visible: !!text
            color: Theme.danger
            wrapMode: Text.Wrap
            Layout.maximumWidth: pane.width - 50
        }
        Item {
            Layout.fillHeight: true
            Layout.minimumHeight: 0
        }
    }
}
